/**
 * Framework-independent state and command helpers for immutable workflow runs.
 * The server remains authoritative for capture, validation, lifecycle and result provenance.
 */

/** @typedef {'LIVE_SESSION' | 'STORED_COMMIT'} RunSourceKind */
/** @typedef {'QUEUED' | 'RUNNING' | 'CANCEL_REQUESTED' | 'CANCELLED' | 'COMPLETED' | 'FAILED'} RunLifecycleState */
/** @typedef {'SIMULATION' | 'COMPUTATION'} RunMode */
/**
 * @typedef {{
 *   sourceKind: RunSourceKind,
 *   startCommandId: string,
 *   sessionId: string | null,
 *   expectedRevision: number | null,
 *   commitId: string | null
 * }} RunCommand
 */
/**
 * @typedef {{
 *   kind: RunSourceKind,
 *   sessionId: string | null,
 *   semanticRevision: number | null,
 *   commitId: string | null
 * }} RunSource
 */
/**
 * @typedef {{
 *   code: string,
 *   message: string,
 *   nodeId: string | null
 * }} RunViolation
 */
/**
 * @typedef {{
 *   runId: string,
 *   startCommandId: string,
 *   state: RunLifecycleState,
 *   mode: RunMode,
 *   source: RunSource,
 *   workflowId: string,
 *   snapshotId: string,
 *   planId: string,
 *   fingerprint: string,
 *   capturedAt: string,
 *   startedAt: string | null,
 *   finishedAt: string | null,
 *   progressPercent: number,
 *   statusMessage: string,
 *   violations: RunViolation[]
 * }} RunSnapshot
 */
/**
 * @typedef {{
 *   run: RunSnapshot,
 *   overallStatus: string,
 *   nodeStatuses: Record<string, string>,
 *   executionStartedAt: string,
 *   executionCompletedAt: string,
 *   commitId: string | null,
 *   artifacts: Record<string, string>
 * }} RunResult
 */
/**
 * @typedef {{
 *   phase: 'idle' | 'starting' | 'uncertain' | 'active' | 'terminal' | 'error',
 *   command: RunCommand | null,
 *   run: RunSnapshot | null,
 *   result: RunResult | null,
 *   problem: Record<string, unknown> | null
 * }} WorkflowRunState
 */

const STATE_RANK = Object.freeze({
  QUEUED: 0,
  RUNNING: 1,
  CANCEL_REQUESTED: 2,
  CANCELLED: 3,
  COMPLETED: 3,
  FAILED: 3,
});

/** @returns {WorkflowRunState} empty run-controller state */
export function emptyWorkflowRunState() {
  return {
    phase: 'idle',
    command: null,
    run: null,
    result: null,
    problem: null,
  };
}

/**
 * @param {string} commandId stable idempotency identity
 * @param {string} sessionId exact collaboration session
 * @param {number} expectedRevision exact displayed semantic revision
 * @returns {Readonly<RunCommand>} immutable live-session command
 */
export function createLiveRunCommand(commandId, sessionId, expectedRevision) {
  requireIdentifier(commandId, 'startCommandId');
  requireIdentifier(sessionId, 'sessionId');
  if (!Number.isSafeInteger(expectedRevision) || expectedRevision < 0) {
    throw new Error('Live workflow runs require a non-negative expected revision');
  }
  return Object.freeze({
    sourceKind: /** @type {'LIVE_SESSION'} */ ('LIVE_SESSION'),
    startCommandId: commandId,
    sessionId,
    expectedRevision,
    commitId: null,
  });
}

/**
 * @param {string} commandId stable idempotency identity
 * @param {string} commitId exact historical commit
 * @returns {Readonly<RunCommand>} immutable historical command
 */
export function createStoredRunCommand(commandId, commitId) {
  requireIdentifier(commandId, 'startCommandId');
  requireIdentifier(commitId, 'commitId');
  return Object.freeze({
    sourceKind: /** @type {'STORED_COMMIT'} */ ('STORED_COMMIT'),
    startCommandId: commandId,
    sessionId: null,
    expectedRevision: null,
    commitId,
  });
}

/**
 * @param {RunCommand} command retry-stable command
 * @returns {Record<string, unknown>} exact REST request body
 */
export function runStartRequest(command) {
  if (command.sourceKind === 'LIVE_SESSION') {
    return {
      startCommandId: command.startCommandId,
      sourceKind: command.sourceKind,
      sessionId: command.sessionId,
      expectedRevision: command.expectedRevision,
    };
  }
  return {
    startCommandId: command.startCommandId,
    sourceKind: command.sourceKind,
    commitId: command.commitId,
  };
}

/** @param {RunLifecycleState} state lifecycle state */
export function isTerminalRunState(state) {
  return state === 'CANCELLED' || state === 'COMPLETED' || state === 'FAILED';
}

/**
 * Accepts server snapshots monotonically so duplicate or out-of-order polling cannot regress the UI.
 *
 * @param {RunSnapshot | null} current currently displayed snapshot
 * @param {RunSnapshot} incoming freshly received server snapshot
 * @returns {RunSnapshot} accepted snapshot
 */
export function acceptRunSnapshot(current, incoming) {
  validateSnapshot(incoming);
  if (current === null) {
    return incoming;
  }
  if (current.runId !== incoming.runId) {
    throw new Error(`Run snapshot identity changed from ${current.runId} to ${incoming.runId}`);
  }
  if (isTerminalRunState(current.state)) {
    return current;
  }
  if (isTerminalRunState(incoming.state)) {
    return incoming;
  }
  const currentRank = STATE_RANK[current.state];
  const incomingRank = STATE_RANK[incoming.state];
  if (incomingRank < currentRank) {
    return current;
  }
  if (incomingRank === currentRank && incoming.progressPercent < current.progressPercent) {
    return current;
  }
  return incoming;
}

/**
 * @param {Record<string, unknown> | null} problem RFC 9457 problem
 * @returns {string[]} stable human-readable diagnostics
 */
export function runProblemMessages(problem) {
  if (problem === null) {
    return [];
  }
  const messages = [];
  const violations = Array.isArray(problem.violations) ? problem.violations : [];
  for (const value of violations) {
    if (typeof value !== 'object' || value === null) {
      continue;
    }
    const violation = /** @type {Record<string, unknown>} */ (value);
    const code = typeof violation.code === 'string' ? violation.code : 'VALIDATION';
    const message = typeof violation.message === 'string' ? violation.message : String(value);
    const nodeId = typeof violation.nodeId === 'string' ? ` [${violation.nodeId}]` : '';
    messages.push(`${code}${nodeId}: ${message}`);
  }
  if (messages.length === 0) {
    const detail = problem.detail ?? problem.title;
    if (typeof detail === 'string' && detail.length > 0) {
      messages.push(detail);
    }
  }
  return messages;
}

/**
 * @param {WorkflowRunState} state current state
 * @param {any} event controller event
 * @returns {WorkflowRunState} next state
 */
export function reduceWorkflowRunState(state, event) {
  switch (event.type) {
    case 'START_SUBMITTED':
      return {
        phase: 'starting',
        command: event.command,
        run: null,
        result: null,
        problem: null,
      };
    case 'START_UNCERTAIN':
      return {
        ...state,
        phase: 'uncertain',
        problem: event.problem,
      };
    case 'START_REJECTED':
      return {
        phase: 'error',
        command: null,
        run: null,
        result: null,
        problem: event.problem,
      };
    case 'START_ACCEPTED': {
      const run = acceptRunSnapshot(null, event.run);
      return {
        phase: isTerminalRunState(run.state) ? 'terminal' : 'active',
        command: state.command,
        run,
        result: null,
        problem: null,
      };
    }
    case 'SNAPSHOT_RECEIVED': {
      const run = acceptRunSnapshot(state.run, event.run);
      return {
        ...state,
        phase: isTerminalRunState(run.state) ? 'terminal' : 'active',
        run,
        problem: null,
      };
    }
    case 'POLL_FAILED':
      return {
        ...state,
        problem: event.problem,
      };
    case 'RESULT_LOADED':
      return {
        ...state,
        phase: 'terminal',
        result: event.result,
        problem: null,
      };
    case 'RESULT_UNAVAILABLE':
      return {
        ...state,
        phase: 'terminal',
        problem: event.problem,
      };
    case 'RESET':
      return emptyWorkflowRunState();
    default:
      throw new Error(`Unsupported workflow run event: ${event.type}`);
  }
}

/** @param {string} value identifier @param {string} name field name */
function requireIdentifier(value, name) {
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw new Error(`${name} must not be blank`);
  }
}

/** @param {RunSnapshot} snapshot server run snapshot */
function validateSnapshot(snapshot) {
  requireIdentifier(snapshot.runId, 'runId');
  requireIdentifier(snapshot.startCommandId, 'startCommandId');
  requireIdentifier(snapshot.fingerprint, 'fingerprint');
  if (!(snapshot.state in STATE_RANK)) {
    throw new Error(`Unsupported workflow run state: ${snapshot.state}`);
  }
  if (!Number.isInteger(snapshot.progressPercent) || snapshot.progressPercent < 0 || snapshot.progressPercent > 100) {
    throw new Error('Workflow run progress must be an integer between 0 and 100');
  }
}
