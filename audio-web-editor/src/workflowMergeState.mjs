/** Framework-independent workflow merge request and conflict-state helpers. */

/** @typedef {'BASE' | 'LOCAL' | 'REMOTE' | 'DELETE' | 'CUSTOM'} ResolutionChoice */

/**
 * @param {Array<{conflictId: string}>} conflicts ordered preview conflicts
 * @returns {Record<string, {choice: '' | ResolutionChoice, customValue: string}>} empty decisions
 */
export function emptyMergeResolutions(conflicts) {
  return Object.fromEntries(
    conflicts.map((conflict) => [conflict.conflictId, { choice: '', customValue: '' }]),
  );
}

/**
 * @param {Record<string, {choice: '' | ResolutionChoice, customValue: string}>} resolutions current state
 * @param {string} conflictId conflict identity
 * @param {'' | ResolutionChoice} choice next decision
 * @param {string} customValue optional custom value
 */
export function updateMergeResolution(resolutions, conflictId, choice, customValue = '') {
  if (!(conflictId in resolutions)) {
    throw new Error(`Unknown merge conflict: ${conflictId}`);
  }
  return {
    ...resolutions,
    [conflictId]: {
      choice,
      customValue: choice === 'CUSTOM' ? customValue : '',
    },
  };
}

/**
 * @param {{conflicts: Array<{conflictId: string, allowedChoices: ResolutionChoice[]}>, validationViolations: string[]}} preview preview response
 * @param {Record<string, {choice: '' | ResolutionChoice, customValue: string}>} resolutions decisions
 */
export function mergeDecisionsComplete(preview, resolutions) {
  if (preview.validationViolations.length > 0) {
    return false;
  }
  return preview.conflicts.every((conflict) => {
    const resolution = resolutions[conflict.conflictId];
    if (resolution === undefined || resolution.choice === '') {
      return false;
    }
    if (!conflict.allowedChoices.includes(resolution.choice)) {
      return false;
    }
    return resolution.choice !== 'CUSTOM' || resolution.customValue.length > 0;
  });
}

/**
 * @param {{
 *  targetBranch: string,
 *  remoteBranch: string,
 *  baseCommitId: string,
 *  localCommitId: string,
 *  remoteCommitId: string,
 *  conflicts: Array<{conflictId: string}>
 * }} preview authoritative preview
 * @param {Record<string, {choice: '' | ResolutionChoice, customValue: string}>} resolutions decisions
 * @param {{author: string, message: string, timestamp: string}} metadata audit metadata
 */
export function mergeResolveRequest(preview, resolutions, metadata) {
  if (metadata.author.trim().length === 0 || metadata.message.trim().length === 0) {
    throw new Error('Merge author and message are required');
  }
  const decisions = preview.conflicts.map((conflict) => {
    const resolution = resolutions[conflict.conflictId];
    if (resolution === undefined || resolution.choice === '') {
      throw new Error(`Merge conflict is unresolved: ${conflict.conflictId}`);
    }
    return {
      conflictId: conflict.conflictId,
      choice: resolution.choice,
      customValue: resolution.choice === 'CUSTOM' ? resolution.customValue : null,
    };
  });
  return Object.freeze({
    targetBranch: preview.targetBranch,
    remoteBranch: preview.remoteBranch,
    baseCommitId: preview.baseCommitId,
    localCommitId: preview.localCommitId,
    remoteCommitId: preview.remoteCommitId,
    expectedHeadCommitId: preview.localCommitId,
    resolutions: decisions,
    author: metadata.author.trim(),
    message: metadata.message.trim(),
    timestamp: metadata.timestamp,
  });
}
