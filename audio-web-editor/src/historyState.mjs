/**
 * Framework-independent durable history state used by the React adapter and Node-native tests.
 * Canonical semantic history is always reloaded from the server.
 */

/** @typedef {'UNDO' | 'REDO'} HistoryCommandKind */
/**
 * @typedef {{
 *   kind: HistoryCommandKind,
 *   commandId: string,
 *   expectedRevision: number,
 *   targetOperationId: string,
 *   previewId: string | null
 * }} HistoryCommandEnvelope
 */
/**
 * @typedef {{
 *   operationId: string,
 *   commandId: string,
 *   revision: number,
 *   [key: string]: unknown
 * }} HistoryOperation
 */
/**
 * @typedef {{
 *   operations: HistoryOperation[],
 *   nextBeforeRevision: number | null,
 *   currentRevision?: number
 * }} HistoryPage
 */
/**
 * @typedef {{
 *   capabilities: object | null,
 *   operations: HistoryOperation[],
 *   nextBeforeRevision: number | null,
 *   status: 'idle' | 'loading' | 'ready' | 'pending' | 'uncertain' | 'error',
 *   pendingCommand: HistoryCommandEnvelope | null,
 *   problem: Record<string, unknown> | null
 * }} HistoryState
 */

const INITIAL_STATE = Object.freeze({
  capabilities: null,
  operations: Object.freeze([]),
  nextBeforeRevision: null,
  status: 'idle',
  pendingCommand: null,
  problem: null,
});

/** @returns {HistoryState} empty client history state */
export function emptyHistoryState() {
  return {
    ...INITIAL_STATE,
    operations: [],
    status: /** @type {'idle'} */ ('idle'),
  };
}

/**
 * Creates an immutable, retry-stable history command envelope.
 *
 * @param {{
 *   kind: HistoryCommandKind,
 *   commandId: string,
 *   expectedRevision: number,
 *   targetOperationId: string,
 *   previewId?: string | null
 * }} input command values
 * @returns {Readonly<HistoryCommandEnvelope>} validated envelope
 */
export function createHistoryCommandEnvelope({
  kind,
  commandId,
  expectedRevision,
  targetOperationId,
  previewId = null,
}) {
  if (kind !== 'UNDO' && kind !== 'REDO') {
    throw new Error(`Unsupported history command kind: ${kind}`);
  }
  if (typeof commandId !== 'string' || commandId.length === 0) {
    throw new Error('History command requires a stable commandId');
  }
  if (!Number.isSafeInteger(expectedRevision) || expectedRevision < 0) {
    throw new Error('History command requires a non-negative expectedRevision');
  }
  if (typeof targetOperationId !== 'string' || targetOperationId.length === 0) {
    throw new Error('History command requires a target operation');
  }
  if (kind === 'REDO' && previewId !== null) {
    throw new Error('Redo commands do not accept an undo preview id');
  }
  return Object.freeze({
    kind,
    commandId,
    expectedRevision,
    targetOperationId,
    previewId,
  });
}

/**
 * Maps one stable envelope to the exact REST command contract.
 *
 * @param {HistoryCommandEnvelope} envelope pending command
 * @param {{actorId: string, userId: string, displayName: string}} actor actor identity
 * @returns {{endpoint: 'undo' | 'redo', body: Record<string, unknown>}} REST request
 */
export function historyCommandRequest(envelope, actor) {
  if (envelope.kind === 'UNDO') {
    return {
      endpoint: 'undo',
      body: {
        commandId: envelope.commandId,
        actor,
        expectedRevision: envelope.expectedRevision,
        targetOperationId: envelope.targetOperationId,
        previewId: envelope.previewId,
      },
    };
  }
  return {
    endpoint: 'redo',
    body: {
      commandId: envelope.commandId,
      actor,
      expectedRevision: envelope.expectedRevision,
      targetUndoOperationId: envelope.targetOperationId,
    },
  };
}

/**
 * @param {HistoryOperation[]} existing current operations
 * @param {HistoryOperation[]} incoming next page
 * @returns {HistoryOperation[]} deduplicated newest-first operations
 */
function uniqueOperations(existing, incoming) {
  const byId = new Map(existing.map((operation) => [operation.operationId, operation]));
  for (const operation of incoming) {
    byId.set(operation.operationId, operation);
  }
  return [...byId.values()].sort((left, right) => right.revision - left.revision);
}

/**
 * @param {HistoryState} state current state
 * @param {HistoryOperation[]} operations freshly loaded durable history
 * @returns {boolean} whether the uncertain command is durably accepted
 */
function acceptedPendingCommand(state, operations) {
  const pendingCommand = state.pendingCommand;
  if (pendingCommand === null) {
    return false;
  }
  return operations.some((operation) => operation.commandId === pendingCommand.commandId);
}

/**
 * Reduces one history controller event. Events are internal to the typed React adapter, so their
 * payload is intentionally transport-agnostic here.
 *
 * @param {HistoryState} state current state
 * @param {any} event controller event
 * @returns {HistoryState} next state
 */
export function reduceHistoryState(state, event) {
  switch (event.type) {
    case 'LOAD_STARTED':
      return {
        ...state,
        status: state.pendingCommand === null ? 'loading' : state.status,
        problem: state.pendingCommand === null ? null : state.problem,
      };
    case 'LOAD_FAILED':
      return {
        ...state,
        status: state.pendingCommand === null ? 'error' : state.status,
        problem: event.problem,
      };
    case 'LOADED': {
      const acceptedPending = acceptedPendingCommand(state, event.page.operations);
      const pendingCommand = acceptedPending ? null : state.pendingCommand;
      return {
        ...state,
        capabilities: event.capabilities,
        operations: [...event.page.operations],
        nextBeforeRevision: event.page.nextBeforeRevision,
        status: pendingCommand === null ? 'ready' : 'uncertain',
        pendingCommand,
        problem: pendingCommand === null ? null : state.problem,
      };
    }
    case 'PAGE_APPENDED':
      return {
        ...state,
        operations: uniqueOperations(state.operations, event.page.operations),
        nextBeforeRevision: event.page.nextBeforeRevision,
        status: state.pendingCommand === null ? 'ready' : state.status,
        problem: state.pendingCommand === null ? null : state.problem,
      };
    case 'COMMAND_STARTED':
      return {
        ...state,
        status: 'pending',
        pendingCommand: event.command,
        problem: null,
      };
    case 'COMMAND_UNCERTAIN':
      return {
        ...state,
        status: 'uncertain',
        problem: event.problem,
      };
    case 'COMMAND_REJECTED':
      return {
        ...state,
        status: 'error',
        pendingCommand: null,
        problem: event.problem,
      };
    case 'COMMAND_ACCEPTED':
      return {
        ...state,
        status: 'loading',
        pendingCommand: null,
        problem: null,
      };
    case 'PROBLEM_CLEARED':
      return {
        ...state,
        status: state.pendingCommand === null ? 'ready' : state.status,
        problem: null,
      };
    case 'RESET':
      return emptyHistoryState();
    default:
      throw new Error(`Unsupported history state event: ${event.type}`);
  }
}

/**
 * @param {string | null} code stable RFC 9457 problem code
 * @returns {'reconcile' | 'reload' | 'reset' | 'reject'} recovery strategy
 */
export function historyRecoveryForProblemCode(code) {
  switch (code) {
    case 'WORKFLOW_SESSION_REVISION_CONFLICT':
    case 'WORKFLOW_SESSION_SEQUENCE_CONFLICT':
    case 'UNDO_PREVIEW_STALE':
      return 'reconcile';
    case 'SESSION_NOT_FOUND':
      return 'reset';
    case 'UNDO_CONFLICT':
    case 'OPERATION_NOT_UNDOABLE':
    case 'UNDO_TARGET_NOT_FOUND':
    case 'REDO_TARGET_NOT_FOUND':
    case 'REDO_TARGET_INVALID':
    case 'REDO_ALREADY_APPLIED':
      return 'reload';
    default:
      return 'reject';
  }
}
