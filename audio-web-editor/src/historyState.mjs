const INITIAL_STATE = Object.freeze({
  capabilities: null,
  operations: Object.freeze([]),
  nextBeforeRevision: null,
  status: 'idle',
  pendingCommand: null,
  problem: null,
});

export function emptyHistoryState() {
  return { ...INITIAL_STATE, operations: [] };
}

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

function uniqueOperations(existing, incoming) {
  const byId = new Map(existing.map((operation) => [operation.operationId, operation]));
  for (const operation of incoming) {
    byId.set(operation.operationId, operation);
  }
  return [...byId.values()].sort((left, right) => right.revision - left.revision);
}

function acceptedPendingCommand(state, operations) {
  if (state.pendingCommand === null) {
    return false;
  }
  return operations.some((operation) => operation.commandId === state.pendingCommand.commandId);
}

export function reduceHistoryState(state, event) {
  switch (event.type) {
    case 'LOAD_STARTED':
      return {
        ...state,
        status: state.pendingCommand === null ? 'loading' : state.status,
        problem: null,
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
