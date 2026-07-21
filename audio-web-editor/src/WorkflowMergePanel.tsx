import { useCallback, useMemo, useState } from 'react';

import { ApiError, postJson, type WorkflowProjection } from './api';
import {
  loadWorkflowBranchHistory,
  previewWorkflowMerge,
  resolveWorkflowMerge,
  type MergeResolutionChoice,
  type WorkflowHistoryEntry,
  type WorkflowMergePreview,
  type WorkflowMergeResolveResponse,
} from './workflowMergeApi';
import {
  emptyMergeResolutions,
  mergeDecisionsComplete,
  mergeResolveRequest,
  updateMergeResolution,
} from './workflowMergeState.mjs';

interface MergeResolutionState {
  choice: '' | MergeResolutionChoice;
  customValue: string;
}

type MergeResolutions = Record<string, MergeResolutionState>;

const ACTIVE_SESSION_STORAGE_KEY = 'audio-analyzer.workflow.active-session';

function failureMessage(failure: unknown): string {
  if (failure instanceof ApiError) {
    return failure.problem?.detail ?? failure.message;
  }
  return failure instanceof Error ? failure.message : String(failure);
}

function activeSessionId(): string | null {
  try {
    const value = sessionStorage.getItem(ACTIVE_SESSION_STORAGE_KEY);
    return value === null || value.trim().length === 0 ? null : value;
  } catch {
    return null;
  }
}

function shortCommit(commitId: string): string {
  return commitId.slice(0, 12);
}

function historyLabel(entry: WorkflowHistoryEntry): string {
  return `${entry.message || shortCommit(entry.commitId)} · ${shortCommit(entry.commitId)}`;
}

function commonBase(
  targetHistory: WorkflowHistoryEntry[],
  remoteHistory: WorkflowHistoryEntry[],
): string {
  const remoteIds = new Set(remoteHistory.map((entry) => entry.commitId));
  return targetHistory.find((entry) => remoteIds.has(entry.commitId))?.commitId ?? '';
}

function nullableValue(value: string | null): string {
  return value === null ? '∅' : value;
}

/** Version-level semantic merge UI; canonical decisions and commits remain server-owned. */
export function WorkflowMergePanel() {
  const [expanded, setExpanded] = useState(false);
  const [targetBranch, setTargetBranch] = useState('main');
  const [remoteBranch, setRemoteBranch] = useState('feature');
  const [targetHistory, setTargetHistory] = useState<WorkflowHistoryEntry[]>([]);
  const [remoteHistory, setRemoteHistory] = useState<WorkflowHistoryEntry[]>([]);
  const [baseCommitId, setBaseCommitId] = useState('');
  const [localCommitId, setLocalCommitId] = useState('');
  const [remoteCommitId, setRemoteCommitId] = useState('');
  const [preview, setPreview] = useState<WorkflowMergePreview | null>(null);
  const [resolutions, setResolutions] = useState<MergeResolutions>({});
  const [author, setAuthor] = useState('Workflow merger');
  const [message, setMessage] = useState('Merge semantic workflow versions');
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState('Load exact branch histories to prepare a semantic merge.');
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<WorkflowMergeResolveResponse | null>(null);

  const historiesReady =
    targetHistory.length > 0 &&
    remoteHistory.length > 0 &&
    baseCommitId !== '' &&
    localCommitId !== '' &&
    remoteCommitId !== '';

  const decisionsComplete = useMemo(
    () => preview !== null && mergeDecisionsComplete(preview, resolutions),
    [preview, resolutions],
  );

  const invalidatePreview = useCallback(() => {
    setPreview(null);
    setResolutions({});
    setResult(null);
  }, []);

  const loadHistories = useCallback(async () => {
    setBusy(true);
    setError(null);
    setStatus('Loading branch-reachable checkpoints…');
    try {
      const [target, remote] = await Promise.all([
        loadWorkflowBranchHistory(targetBranch),
        loadWorkflowBranchHistory(remoteBranch),
      ]);
      setTargetHistory(target);
      setRemoteHistory(remote);
      const nextLocal = target[0]?.commitId ?? '';
      const nextRemote = remote[0]?.commitId ?? '';
      const nextBase = commonBase(target, remote);
      setLocalCommitId(nextLocal);
      setRemoteCommitId(nextRemote);
      setBaseCommitId(nextBase);
      invalidatePreview();
      setStatus(
        nextBase === ''
          ? 'No common branch-reachable checkpoint was found; select or create a shared base.'
          : `Loaded ${target.length} target and ${remote.length} remote checkpoints.`,
      );
    } catch (failure) {
      setError(failureMessage(failure));
      setStatus('Stored merge history is unavailable in the current persistence mode.');
    } finally {
      setBusy(false);
    }
  }, [invalidatePreview, remoteBranch, targetBranch]);

  const requestPreview = useCallback(async () => {
    if (!historiesReady) {
      return;
    }
    setBusy(true);
    setError(null);
    setResult(null);
    setStatus('Calculating deterministic base/local/remote merge…');
    try {
      const next = await previewWorkflowMerge({
        targetBranch,
        remoteBranch,
        baseCommitId,
        localCommitId,
        remoteCommitId,
      });
      setPreview(next);
      setResolutions(emptyMergeResolutions(next.conflicts) as MergeResolutions);
      setStatus(
        next.readyToCommit
          ? 'All changes merge automatically and the candidate is valid.'
          : `${next.conflicts.length} conflict${next.conflicts.length === 1 ? '' : 's'} and ${next.validationViolations.length} validation violation${next.validationViolations.length === 1 ? '' : 's'} require attention.`,
      );
    } catch (failure) {
      setError(failureMessage(failure));
      setStatus('Merge preview failed.');
    } finally {
      setBusy(false);
    }
  }, [baseCommitId, historiesReady, localCommitId, remoteBranch, remoteCommitId, targetBranch]);

  const updateChoice = useCallback(
    (conflictId: string, choice: '' | MergeResolutionChoice) => {
      setResolutions((current) =>
        updateMergeResolution(current, conflictId, choice) as MergeResolutions,
      );
      setResult(null);
    },
    [],
  );

  const updateCustomValue = useCallback((conflictId: string, customValue: string) => {
    setResolutions((current) =>
      updateMergeResolution(current, conflictId, 'CUSTOM', customValue) as MergeResolutions,
    );
    setResult(null);
  }, []);

  const commitMerge = useCallback(async () => {
    if (preview === null || !decisionsComplete) {
      return;
    }
    setBusy(true);
    setError(null);
    setStatus('Validating and committing the resolved workflow…');
    try {
      const request = mergeResolveRequest(preview, resolutions, {
        author,
        message,
        timestamp: new Date().toISOString(),
      });
      const committed = await resolveWorkflowMerge(request);
      setResult(committed);
      setStatus(`Created merge checkpoint ${shortCommit(committed.mergedCommitId)}.`);
      const refreshed = await loadWorkflowBranchHistory(targetBranch);
      setTargetHistory(refreshed);
    } catch (failure) {
      setError(failureMessage(failure));
      setStatus('Resolved merge was not committed.');
    } finally {
      setBusy(false);
    }
  }, [author, decisionsComplete, message, preview, resolutions, targetBranch]);

  const loadMergedCommit = useCallback(async () => {
    if (result === null) {
      return;
    }
    const sessionId = activeSessionId();
    if (sessionId !== null) {
      setError(`Leave collaboration session ${sessionId} before loading the merge checkpoint.`);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await postJson<WorkflowProjection>('/workflow/load', { commitId: result.mergedCommitId });
      window.location.reload();
    } catch (failure) {
      setError(failureMessage(failure));
      setBusy(false);
    }
  }, [result]);

  return (
    <section className="workflow-merge" data-testid="workflow-merge-panel">
      <button
        aria-expanded={expanded}
        className="workflow-merge__toggle"
        data-testid="workflow-merge-toggle"
        onClick={() => setExpanded((current) => !current)}
        type="button"
      >
        {expanded ? 'Close semantic merge' : 'Merge workflow versions'}
      </button>
      {expanded ? (
        <div className="workflow-merge__drawer">
          <header>
            <h2>Semantic three-way merge</h2>
            <p>
              Select exact branch-reachable base, local and remote commits. Raw DSL conflicts are
              never exposed as the resolution model.
            </p>
          </header>

          <div className="workflow-merge__branches">
            <label className="field">
              Target branch
              <input
                data-testid="merge-target-branch"
                value={targetBranch}
                onChange={(event) => {
                  setTargetBranch(event.target.value);
                  setTargetHistory([]);
                  invalidatePreview();
                }}
              />
            </label>
            <label className="field">
              Remote branch
              <input
                data-testid="merge-remote-branch"
                value={remoteBranch}
                onChange={(event) => {
                  setRemoteBranch(event.target.value);
                  setRemoteHistory([]);
                  invalidatePreview();
                }}
              />
            </label>
            <button
              className="action-button"
              data-testid="merge-load-histories"
              disabled={busy || targetBranch.trim() === '' || remoteBranch.trim() === ''}
              onClick={() => void loadHistories()}
              type="button"
            >
              Load exact histories
            </button>
          </div>

          <div className="workflow-merge__versions">
            <label className="field">
              Common base
              <select
                data-testid="merge-base-commit"
                disabled={busy || targetHistory.length === 0}
                value={baseCommitId}
                onChange={(event) => {
                  setBaseCommitId(event.target.value);
                  invalidatePreview();
                }}
              >
                <option value="">Select common base</option>
                {targetHistory.map((entry) => (
                  <option key={entry.commitId} value={entry.commitId}>
                    {historyLabel(entry)}
                  </option>
                ))}
              </select>
            </label>
            <label className="field">
              Local / expected target HEAD
              <select
                data-testid="merge-local-commit"
                disabled={busy || targetHistory.length === 0}
                value={localCommitId}
                onChange={(event) => {
                  setLocalCommitId(event.target.value);
                  invalidatePreview();
                }}
              >
                {targetHistory.map((entry) => (
                  <option key={entry.commitId} value={entry.commitId}>
                    {historyLabel(entry)}
                  </option>
                ))}
              </select>
            </label>
            <label className="field">
              Remote
              <select
                data-testid="merge-remote-commit"
                disabled={busy || remoteHistory.length === 0}
                value={remoteCommitId}
                onChange={(event) => {
                  setRemoteCommitId(event.target.value);
                  invalidatePreview();
                }}
              >
                {remoteHistory.map((entry) => (
                  <option key={entry.commitId} value={entry.commitId}>
                    {historyLabel(entry)}
                  </option>
                ))}
              </select>
            </label>
          </div>

          <button
            className="action-button"
            data-testid="merge-preview"
            disabled={busy || !historiesReady}
            onClick={() => void requestPreview()}
            type="button"
          >
            Preview semantic merge
          </button>

          <p className="help-text" data-testid="merge-status">
            {status}
          </p>
          {error === null ? null : (
            <p className="workbench__error" data-testid="merge-error">
              {error}
            </p>
          )}

          {preview === null ? null : (
            <div className="workflow-merge__preview" data-testid="merge-preview-result">
              <dl className="workflow-merge__summary">
                <div>
                  <dt>Base</dt>
                  <dd>{shortCommit(preview.baseCommitId)}</dd>
                </div>
                <div>
                  <dt>Local</dt>
                  <dd>{shortCommit(preview.localCommitId)}</dd>
                </div>
                <div>
                  <dt>Remote</dt>
                  <dd>{shortCommit(preview.remoteCommitId)}</dd>
                </div>
                <div>
                  <dt>Automatic candidate</dt>
                  <dd>
                    {preview.autoMerged.nodes.length} nodes · {preview.autoMerged.edges.length} edges
                  </dd>
                </div>
              </dl>

              {preview.validationViolations.length === 0 ? null : (
                <ul className="validation-list" data-testid="merge-validation-violations">
                  {preview.validationViolations.map((violation) => (
                    <li key={violation}>{violation}</li>
                  ))}
                </ul>
              )}

              <div data-testid="merge-conflict-list">
                {preview.conflicts.map((conflict) => {
                  const resolution = resolutions[conflict.conflictId] ?? {
                    choice: '',
                    customValue: '',
                  };
                  return (
                    <article
                      className="workflow-merge__conflict"
                      data-testid={`merge-conflict-${conflict.conflictId}`}
                      key={conflict.conflictId}
                    >
                      <header>
                        <strong>{conflict.kind}</strong>
                        <span>
                          {conflict.elementKind} {conflict.elementId} · {conflict.fieldPath}
                        </span>
                      </header>
                      <div className="workflow-merge__values">
                        <div>
                          <span>Base</span>
                          <code>{nullableValue(conflict.baseValue)}</code>
                        </div>
                        <div>
                          <span>Local</span>
                          <code>{nullableValue(conflict.localValue)}</code>
                        </div>
                        <div>
                          <span>Remote</span>
                          <code>{nullableValue(conflict.remoteValue)}</code>
                        </div>
                      </div>
                      <label className="field">
                        Resolution
                        <select
                          data-testid={`merge-resolution-${conflict.conflictId}`}
                          value={resolution.choice}
                          onChange={(event) =>
                            updateChoice(
                              conflict.conflictId,
                              event.target.value as '' | MergeResolutionChoice,
                            )
                          }
                        >
                          <option value="">Select decision</option>
                          {conflict.allowedChoices.map((choice) => (
                            <option key={choice} value={choice}>
                              {choice}
                            </option>
                          ))}
                        </select>
                      </label>
                      {resolution.choice === 'CUSTOM' ? (
                        <label className="field">
                          Explicit merged value
                          <input
                            data-testid={`merge-custom-${conflict.conflictId}`}
                            value={resolution.customValue}
                            onChange={(event) =>
                              updateCustomValue(conflict.conflictId, event.target.value)
                            }
                          />
                        </label>
                      ) : null}
                    </article>
                  );
                })}
              </div>

              <div className="workflow-merge__commit">
                <label className="field">
                  Author
                  <input
                    data-testid="merge-author"
                    value={author}
                    onChange={(event) => setAuthor(event.target.value)}
                  />
                </label>
                <label className="field">
                  Checkpoint message
                  <input
                    data-testid="merge-message"
                    value={message}
                    onChange={(event) => setMessage(event.target.value)}
                  />
                </label>
                <button
                  className="action-button"
                  data-testid="merge-commit"
                  disabled={busy || !decisionsComplete || author.trim() === '' || message.trim() === ''}
                  onClick={() => void commitMerge()}
                  type="button"
                >
                  Commit resolved workflow
                </button>
              </div>
            </div>
          )}

          {result === null ? null : (
            <div className="workflow-merge__result" data-testid="merge-commit-result">
              <strong>Merge checkpoint {result.mergedCommitId}</strong>
              <p>
                Reloaded {result.workflow.workflowName} with {result.workflow.nodes.length} nodes and{' '}
                {result.workflow.edges.length} edges.
              </p>
              <button
                className="action-button"
                data-testid="merge-load-result"
                disabled={busy}
                onClick={() => void loadMergedCommit()}
                type="button"
              >
                Load exact merge commit
              </button>
            </div>
          )}
        </div>
      ) : null}
    </section>
  );
}
