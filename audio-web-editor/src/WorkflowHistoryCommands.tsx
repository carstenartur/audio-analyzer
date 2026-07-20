import { useState } from 'react';

import { getJson, postJson, type WorkflowProjection } from './api';

interface HistoryCommandHit {
  commitId: string;
  message: string;
}

interface HistoryEntry {
  commitId: string;
}

interface ComparisonChange {
  kind: string;
  targetId: string;
  propertyKey: string | null;
  oldValue: string | null;
  newValue: string | null;
}

interface ComparisonResponse {
  beforeCommitId: string;
  afterCommitId: string;
  before: WorkflowProjection;
  after: WorkflowProjection;
  changes: ComparisonChange[];
}

interface RestoreResponse {
  branch: string;
  targetCommitId: string;
  previousHeadCommitId: string;
  restoredCommitId: string;
}

interface WorkflowHistoryCommandsProps {
  branch: string;
  hits: HistoryCommandHit[];
  disabled: boolean;
  onStatus: (status: string) => void;
  onError: (error: string | null) => void;
}

const ACTIVE_SESSION_STORAGE_KEY = 'audio-analyzer.workflow.active-session';

function activeSessionId(): string | null {
  try {
    const sessionId = sessionStorage.getItem(ACTIVE_SESSION_STORAGE_KEY);
    return sessionId === null || sessionId.trim().length === 0 ? null : sessionId;
  } catch {
    return null;
  }
}

function failureMessage(failure: unknown): string {
  return failure instanceof Error ? failure.message : String(failure);
}

function shortCommit(commitId: string): string {
  return commitId.slice(0, 12);
}

function optionLabel(hit: HistoryCommandHit): string {
  return `${shortCommit(hit.commitId)} · ${hit.message || 'Checkpoint'}`;
}

/** Explicit compare and non-destructive restore controls for indexed history results. */
export function WorkflowHistoryCommands({
  branch,
  hits,
  disabled,
  onStatus,
  onError,
}: WorkflowHistoryCommandsProps) {
  const [beforeCommitId, setBeforeCommitId] = useState('');
  const [afterCommitId, setAfterCommitId] = useState('');
  const [restoreCommitId, setRestoreCommitId] = useState('');
  const [comparison, setComparison] = useState<ComparisonResponse | null>(null);
  const [comparing, setComparing] = useState(false);
  const [restoring, setRestoring] = useState(false);

  const compare = async () => {
    if (branch.trim().length === 0 || beforeCommitId.length === 0 || afterCommitId.length === 0) {
      onError('Choose a branch and two exact commits before comparing.');
      return;
    }
    if (beforeCommitId === afterCommitId) {
      onError('Choose two different commits for comparison.');
      return;
    }
    setComparing(true);
    onError(null);
    try {
      const response = await postJson<ComparisonResponse>('/workflow/history/compare', {
        branch,
        beforeCommitId,
        afterCommitId,
      });
      setComparison(response);
      onStatus(
        `Compared ${shortCommit(response.beforeCommitId)} with ${shortCommit(response.afterCommitId)}: ${response.changes.length} semantic change${response.changes.length === 1 ? '' : 's'}.`,
      );
    } catch (failure) {
      onError(failureMessage(failure));
    } finally {
      setComparing(false);
    }
  };

  const restore = async () => {
    if (branch.trim().length === 0 || restoreCommitId.length === 0) {
      onError('Choose a branch and exact historical commit before restoring.');
      return;
    }
    const sessionId = activeSessionId();
    if (sessionId !== null) {
      onError(`Leave collaboration session ${sessionId} before restoring historical state.`);
      return;
    }
    setRestoring(true);
    onError(null);
    try {
      const head = await getJson<HistoryEntry[]>(
        `/workflow/history?branch=${encodeURIComponent(branch)}&limit=1`,
      );
      if (head.length === 0) {
        throw new Error(`Branch ${branch} has no checkpoint HEAD.`);
      }
      const response = await postJson<RestoreResponse>('/workflow/history/restore', {
        branch,
        targetCommitId: restoreCommitId,
        expectedHeadCommitId: head[0].commitId,
        author: 'web-editor',
        message: `Restore workflow version ${shortCommit(restoreCommitId)}`,
        timestamp: new Date().toISOString(),
      });
      await postJson<WorkflowProjection>('/workflow/load', { commitId: response.restoredCommitId });
      onStatus(
        `Restored ${shortCommit(response.targetCommitId)} as new commit ${shortCommit(response.restoredCommitId)}. Refreshing the workbench…`,
      );
      window.location.reload();
    } catch (failure) {
      onError(failureMessage(failure));
      setRestoring(false);
    }
  };

  return (
    <section className="indexed-history__commands" data-testid="indexed-history-commands">
      <h3>Compare and restore exact versions</h3>
      <p>
        Comparison is read-only. Restore creates a new audit commit and requires the branch HEAD to
        remain unchanged.
      </p>
      <div className="indexed-history__command-grid">
        <label className="field">
          Before commit
          <select
            data-testid="indexed-history-compare-before"
            value={beforeCommitId}
            onChange={(event) => setBeforeCommitId(event.target.value)}
          >
            <option value="">Choose a result…</option>
            {hits.map((hit) => (
              <option key={hit.commitId} value={hit.commitId}>
                {optionLabel(hit)}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          After commit
          <select
            data-testid="indexed-history-compare-after"
            value={afterCommitId}
            onChange={(event) => setAfterCommitId(event.target.value)}
          >
            <option value="">Choose a result…</option>
            {hits.map((hit) => (
              <option key={hit.commitId} value={hit.commitId}>
                {optionLabel(hit)}
              </option>
            ))}
          </select>
        </label>
        <button
          className="action-button"
          data-testid="indexed-history-compare"
          disabled={disabled || comparing || restoring}
          onClick={() => void compare()}
          type="button"
        >
          {comparing ? 'Comparing…' : 'Compare exact commits'}
        </button>
      </div>

      {comparison === null ? null : (
        <div className="indexed-history__comparison" data-testid="indexed-history-comparison">
          <strong>
            {comparison.before.workflowName} → {comparison.after.workflowName}
          </strong>
          {comparison.changes.length === 0 ? (
            <p>No semantic changes.</p>
          ) : (
            <ul>
              {comparison.changes.map((change, index) => (
                <li key={`${change.kind}-${change.targetId}-${index}`}>
                  <code>{change.kind}</code> {change.targetId}
                  {change.propertyKey === null ? '' : ` · ${change.propertyKey}`}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      <div className="indexed-history__command-grid">
        <label className="field">
          Version to restore
          <select
            data-testid="indexed-history-restore-target"
            value={restoreCommitId}
            onChange={(event) => setRestoreCommitId(event.target.value)}
          >
            <option value="">Choose a result…</option>
            {hits.map((hit) => (
              <option key={hit.commitId} value={hit.commitId}>
                {optionLabel(hit)}
              </option>
            ))}
          </select>
        </label>
        <button
          className="action-button"
          data-testid="indexed-history-restore"
          disabled={disabled || comparing || restoring}
          onClick={() => void restore()}
          type="button"
        >
          {restoring ? 'Restoring as new commit…' : 'Restore as new commit'}
        </button>
      </div>
    </section>
  );
}
