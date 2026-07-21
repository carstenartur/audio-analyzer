import assert from 'node:assert/strict';
import test from 'node:test';

import {
  acceptRunSnapshot,
  createLiveRunCommand,
  createStoredRunCommand,
  emptyWorkflowRunState,
  isTerminalRunState,
  reduceWorkflowRunState,
  runProblemMessages,
  runStartRequest,
} from '../src/workflowRunState.mjs';

function snapshot(overrides = {}) {
  return {
    runId: 'run-1',
    startCommandId: 'command-1',
    state: 'RUNNING',
    mode: 'COMPUTATION',
    source: {
      kind: 'LIVE_SESSION',
      sessionId: 'session-1',
      semanticRevision: 7,
      commitId: null,
    },
    workflowId: 'workflow-1',
    snapshotId: 'snapshot-1',
    planId: 'plan-1',
    fingerprint: 'a'.repeat(64),
    capturedAt: '2026-07-20T12:00:00Z',
    startedAt: '2026-07-20T12:00:01Z',
    finishedAt: null,
    progressPercent: 40,
    statusMessage: 'Running',
    violations: [],
    ...overrides,
  };
}

test('live and stored commands map to exact retry-stable REST requests', () => {
  const live = createLiveRunCommand('command-live', 'session-live', 12);
  const stored = createStoredRunCommand('command-stored', 'abcdef123456');

  assert.deepEqual(runStartRequest(live), {
    startCommandId: 'command-live',
    sourceKind: 'LIVE_SESSION',
    sessionId: 'session-live',
    expectedRevision: 12,
  });
  assert.deepEqual(runStartRequest(stored), {
    startCommandId: 'command-stored',
    sourceKind: 'STORED_COMMIT',
    commitId: 'abcdef123456',
  });
  assert.throws(() => createLiveRunCommand('command', 'session', -1));
  assert.throws(() => createStoredRunCommand('command', ''));
});

test('uncertain start keeps the same command for an idempotent retry', () => {
  const command = createLiveRunCommand('command-retry', 'session-1', 3);
  let state = reduceWorkflowRunState(emptyWorkflowRunState(), {
    type: 'START_SUBMITTED',
    command,
  });
  state = reduceWorkflowRunState(state, {
    type: 'START_UNCERTAIN',
    problem: { detail: 'connection reset' },
  });

  assert.equal(state.phase, 'uncertain');
  assert.equal(state.command, command);
  assert.deepEqual(runStartRequest(state.command), runStartRequest(command));

  state = reduceWorkflowRunState(state, {
    type: 'START_ACCEPTED',
    run: snapshot({ startCommandId: 'command-retry' }),
  });
  assert.equal(state.phase, 'active');
  assert.equal(state.run?.runId, 'run-1');
});

test('out-of-order polling cannot regress state or progress', () => {
  const current = snapshot({ state: 'CANCEL_REQUESTED', progressPercent: 70 });

  assert.equal(
    acceptRunSnapshot(current, snapshot({ state: 'RUNNING', progressPercent: 90 })),
    current,
  );
  assert.equal(
    acceptRunSnapshot(current, snapshot({ state: 'CANCEL_REQUESTED', progressPercent: 60 })),
    current,
  );
  assert.equal(
    acceptRunSnapshot(current, snapshot({ state: 'CANCEL_REQUESTED', progressPercent: 80 }))
      .progressPercent,
    80,
  );
});

test('terminal state dominates later duplicate or stale responses', () => {
  const completed = snapshot({
    state: 'COMPLETED',
    progressPercent: 100,
    finishedAt: '2026-07-20T12:00:02Z',
  });

  assert.equal(isTerminalRunState(completed.state), true);
  assert.equal(acceptRunSnapshot(completed, snapshot({ progressPercent: 90 })), completed);

  const acceptedTerminal = acceptRunSnapshot(snapshot(), completed);
  assert.equal(acceptedTerminal.state, 'COMPLETED');
  assert.equal(acceptedTerminal.progressPercent, 100);
});

test('structured validation problems retain node identity and stable code', () => {
  assert.deepEqual(
    runProblemMessages({
      detail: 'preflight failed',
      violations: [
        {
          code: 'UNSUPPORTED_NODE',
          nodeId: 'node.fft',
          message: 'No deterministic executor',
        },
      ],
    }),
    ['UNSUPPORTED_NODE [node.fft]: No deterministic executor'],
  );
  assert.deepEqual(runProblemMessages({ detail: 'plain failure' }), ['plain failure']);
});

test('loaded server result completes the state without rebuilding browser input', () => {
  const command = createStoredRunCommand('command-history', 'commit-1');
  let state = reduceWorkflowRunState(emptyWorkflowRunState(), {
    type: 'START_SUBMITTED',
    command,
  });
  state = reduceWorkflowRunState(state, {
    type: 'START_ACCEPTED',
    run: snapshot({
      startCommandId: command.startCommandId,
      state: 'COMPLETED',
      progressPercent: 100,
      source: {
        kind: 'STORED_COMMIT',
        sessionId: null,
        semanticRevision: null,
        commitId: 'commit-1',
      },
    }),
  });
  const result = {
    run: state.run,
    overallStatus: 'COMPLETED',
    nodeStatuses: { 'node.gain': 'COMPLETED' },
    executionStartedAt: '2026-07-20T12:00:01Z',
    executionCompletedAt: '2026-07-20T12:00:02Z',
    commitId: 'commit-1',
    artifacts: { 'output.digest.sha256': 'b'.repeat(64) },
  };
  state = reduceWorkflowRunState(state, { type: 'RESULT_LOADED', result });

  assert.equal(state.phase, 'terminal');
  assert.equal(state.result, result);
  assert.equal(state.result?.commitId, 'commit-1');
});
