/** @typedef {'PRIVATE_WORKSPACE' | 'SHARED_SESSION_PERSONAL_UNDO' | 'SHARED_SESSION_SHARED_UNDO'} CollaborationMode */
/** @typedef {'idle' | 'loading' | 'ready' | 'pending' | 'uncertain' | 'error'} HistoryStatus */
/**
 * @typedef {{
 *   operation: {operationId: string},
 *   status: 'AVAILABLE' | 'BLOCKED' | 'NOT_RECONSTRUCTIBLE',
 *   available: boolean
 * }} HistoryAction
 */
/**
 * @typedef {{
 *   mode: CollaborationMode,
 *   personalUndoPermitted: boolean,
 *   personalUndo: HistoryAction | null,
 *   redo: HistoryAction | null,
 *   sharedUndoPermitted: boolean
 * }} HistoryCapabilities
 */

/**
 * @param {CollaborationMode} mode collaboration mode
 * @returns {string} concise user-facing semantics
 */
export function historyModeExplanation(mode) {
  switch (mode) {
    case 'PRIVATE_WORKSPACE':
      return 'Undo and redo apply only to your private semantic operation history.';
    case 'SHARED_SESSION_PERSONAL_UNDO':
      return 'Everyone sees accepted changes, while undo and redo target only your own history.';
    case 'SHARED_SESSION_SHARED_UNDO':
      return 'Undo may target any active shared operation, but always requires explicit selection and confirmation.';
    default:
      throw new Error(`Unsupported collaboration mode: ${mode}`);
  }
}

/**
 * @param {{
 *   capabilities: HistoryCapabilities | null,
 *   connectionState: string,
 *   historyStatus: HistoryStatus
 * }} input current server and transport state
 * @returns {{
 *   busy: boolean,
 *   personalUndoVisible: boolean,
 *   personalUndoEnabled: boolean,
 *   personalUndoReason: string,
 *   redoVisible: boolean,
 *   redoEnabled: boolean,
 *   redoReason: string,
 *   sharedUndoVisible: boolean,
 *   sharedUndoEnabled: boolean
 * }} control policy
 */
export function historyControlPolicy({ capabilities, connectionState, historyStatus }) {
  const live = connectionState === 'live';
  const busy = historyStatus === 'loading' || historyStatus === 'pending';
  const ready = historyStatus === 'ready';
  const usable = live && ready;
  const personalUndo = capabilities?.personalUndo ?? null;
  const redo = capabilities?.redo ?? null;

  return {
    busy,
    personalUndoVisible: capabilities?.personalUndoPermitted === true,
    personalUndoEnabled: usable && personalUndo?.available === true,
    personalUndoReason: actionReason(personalUndo, live, busy, ready, 'undo'),
    redoVisible: capabilities !== null,
    redoEnabled: usable && redo?.available === true,
    redoReason: actionReason(redo, live, busy, ready, 'redo'),
    sharedUndoVisible: capabilities?.sharedUndoPermitted === true,
    sharedUndoEnabled: usable && capabilities?.sharedUndoPermitted === true,
  };
}

/**
 * @param {HistoryAction | null} action server-reported action
 * @param {boolean} live transport state
 * @param {boolean} busy command state
 * @param {boolean} ready whether capabilities are current and usable
 * @param {'undo' | 'redo'} kind action kind
 * @returns {string} user-facing unavailable reason
 */
function actionReason(action, live, busy, ready, kind) {
  if (!live) {
    return `${kind} is unavailable until the collaboration stream is live.`;
  }
  if (busy) {
    return `A history request is already in progress.`;
  }
  if (!ready) {
    return `Durable history capabilities must be reloaded before ${kind}.`;
  }
  if (action === null) {
    return `No current ${kind} target is reported by the server.`;
  }
  if (action.status === 'BLOCKED') {
    return `The current ${kind} target is blocked by later semantic operations.`;
  }
  if (action.status === 'NOT_RECONSTRUCTIBLE') {
    return `The current ${kind} target predates reconstructible semantic history.`;
  }
  return action.available ? '' : `The current ${kind} target is unavailable.`;
}

/**
 * Maps Ctrl/Cmd+Z to the same visible control path. Shared undo never executes directly.
 *
 * @param {{
 *   mode: CollaborationMode,
 *   shiftKey: boolean,
 *   personalUndoEnabled: boolean,
 *   redoEnabled: boolean,
 *   sharedTargetSelected: boolean
 * }} input shortcut context
 * @returns {'personal-undo' | 'redo' | 'shared-preview' | 'select-shared-target' | 'none'} action
 */
export function historyShortcutAction({
  mode,
  shiftKey,
  personalUndoEnabled,
  redoEnabled,
  sharedTargetSelected,
}) {
  if (shiftKey) {
    return redoEnabled ? 'redo' : 'none';
  }
  if (mode === 'SHARED_SESSION_SHARED_UNDO') {
    return sharedTargetSelected ? 'shared-preview' : 'select-shared-target';
  }
  return personalUndoEnabled ? 'personal-undo' : 'none';
}
