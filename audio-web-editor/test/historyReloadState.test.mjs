import assert from 'node:assert/strict';
import test from 'node:test';

import {
  createHistoryCommandEnvelope,
  emptyHistoryState,
  reduceHistoryState,
} from '../src/historyState.mjs';

const capabilities = {
  mode: 'PRIVATE_WORKSPACE',
  revision: 2,
  personalUndoPermitted: true,
  personalUndo: null,
  redo: null,
  sharedUndoPermitted: false,
};

const page = {
  operations: [{ operationId: 'operation-2', commandId: 'normal-2', revision: 2 }],
  nextBeforeRevision: null,
  currentRevision: 2,
};

test('duplicate canonical reloads are idempotent', () => {
  const once = reduceHistoryState(emptyHistoryState(), { type: 'LOADED', capabilities, page });
  const twice = reduceHistoryState(once, { type: 'LOADED', capabilities, page });

  assert.deepEqual(twice, once);
  assert.equal(twice.operations.length, 1);
});

test('reload failure preserves an uncertain command envelope and its diagnostic', () => {
  const command = createHistoryCommandEnvelope({
    kind: 'UNDO',
    commandId: 'command-uncertain',
    expectedRevision: 2,
    targetOperationId: 'operation-2',
  });
  const uncertain = reduceHistoryState(
    reduceHistoryState(emptyHistoryState(), { type: 'COMMAND_STARTED', command }),
    { type: 'COMMAND_UNCERTAIN', problem: { detail: 'response lost' } },
  );
  const loading = reduceHistoryState(uncertain, { type: 'LOAD_STARTED' });
  const failed = reduceHistoryState(loading, {
    type: 'LOAD_FAILED',
    problem: { detail: 'history unavailable' },
  });

  assert.equal(loading.status, 'uncertain');
  assert.strictEqual(loading.pendingCommand, command);
  assert.deepEqual(loading.problem, { detail: 'response lost' });
  assert.equal(failed.status, 'uncertain');
  assert.strictEqual(failed.pendingCommand, command);
  assert.deepEqual(failed.problem, { detail: 'history unavailable' });
});

test('full session reset clears cached capabilities, history and retry identity', () => {
  const command = createHistoryCommandEnvelope({
    kind: 'REDO',
    commandId: 'command-redo',
    expectedRevision: 2,
    targetOperationId: 'operation-undo',
  });
  const loaded = reduceHistoryState(emptyHistoryState(), { type: 'LOADED', capabilities, page });
  const pending = reduceHistoryState(loaded, { type: 'COMMAND_STARTED', command });
  const reset = reduceHistoryState(pending, { type: 'RESET' });

  assert.deepEqual(reset, emptyHistoryState());
});
