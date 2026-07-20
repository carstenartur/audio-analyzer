import { useCallback, useEffect, useState } from 'react';

import { ApiError, postJson, type WorkflowProjection } from './api';
import {
  historyHitLabel,
  indexedHistoryRebuildUrl,
  localHistoryTimeToInstant,
  normalizeHistorySearchLimit,
  visibleChangedPaths,
} from './indexedHistorySearch.mjs';
import { WorkflowHistoryCommands } from './WorkflowHistoryCommands';

interface SemanticProperty {
  key: string;
  value: string;
}

interface SemanticEvidence {
  branch: string;
  workflowId: string;
  workflowName: string;
  nodeIds: string[];
  nodeTypes: string[];
  nodeLabels: string[];
  properties: SemanticProperty[];
}

interface CombinedHistoryResponse {
  commit: {
    commitId: string;
    message: string;
    authorName: string | null;
    authorEmail: string | null;
    timestamp: string | null;
    changedPaths: string[];
  };
  semantics: SemanticEvidence;
}

interface IndexedHistoryHit {
  commitId: string;
  message: string;
  authorName: string | null;
  authorEmail: string | null;
  timestamp: string | null;
  changedPaths: string[];
  semantics: SemanticEvidence;
}

interface RebuildResponse {
  indexedCommits: number;
}

type Availability = 'probing' | 'available' | 'unavailable';

const ACTIVE_SESSION_STORAGE_KEY = 'audio-analyzer.workflow.active-session';
const COMBINED_HISTORY_URL = '/workflow/history/combined/query';

function failureMessage(failure: unknown): string {
  return failure instanceof Error ? failure.message : String(failure);
}

function activeSessionId(): string | null {
  try {
    const sessionId = sessionStorage.getItem(ACTIVE_SESSION_STORAGE_KEY);
    return sessionId === null || sessionId.trim().length === 0 ? null : sessionId;
  } catch {
    return null;
  }
}

function formattedTimestamp(value: string | null): string {
  if (value === null) {
    return 'Unknown time';
  }
  const timestamp = new Date(value);
  return Number.isNaN(timestamp.getTime()) ? value : timestamp.toLocaleString();
}

function isUnavailable(failure: unknown): boolean {
  return failure instanceof ApiError && failure.status === 404;
}

function toHistoryHits(responses: CombinedHistoryResponse[]): IndexedHistoryHit[] {
  return responses.map((response) => ({
    ...response.commit,
    semantics: response.semantics,
  }));
}

function shortSemanticValues(values: string[]): string {
  if (values.length === 0) {
    return 'none';
  }
  const visible = values.slice(0, 3).join(', ');
  return values.length > 3 ? `${visible}, …` : visible;
}

/** Optional version-history UI, rendered only when combined indexed search is available. */
export function IndexedWorkflowHistoryPanel() {
  const [availability, setAvailability] = useState<Availability>('probing');
  const [expanded, setExpanded] = useState(false);
  const [query, setQuery] = useState('');
  const [authorEmail, setAuthorEmail] = useState('');
  const [pathText, setPathText] = useState('');
  const [fromTime, setFromTime] = useState('');
  const [toTime, setToTime] = useState('');
  const [branch, setBranch] = useState('main');
  const [workflowId, setWorkflowId] = useState('');
  const [nodeId, setNodeId] = useState('');
  const [nodeType, setNodeType] = useState('');
  const [labelText, setLabelText] = useState('');
  const [propertyKey, setPropertyKey] = useState('');
  const [propertyValue, setPropertyValue] = useState('');
  const [limit, setLimit] = useState(20);
  const [hits, setHits] = useState<IndexedHistoryHit[]>([]);
  const [searching, setSearching] = useState(false);
  const [rebuilding, setRebuilding] = useState(false);
  const [loadingCommitId, setLoadingCommitId] = useState<string | null>(null);
  const [status, setStatus] = useState('Derived indexes ready. Git remains authoritative.');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let mounted = true;
    void postJson<CombinedHistoryResponse[]>(COMBINED_HISTORY_URL, {
      generic: { text: '', limit: 1 },
      semantic: { branch: 'main' },
    })
      .then((initialHits) => {
        if (!mounted) {
          return;
        }
        setHits(toHistoryHits(initialHits));
        setAvailability('available');
      })
      .catch((failure: unknown) => {
        if (!mounted) {
          return;
        }
        if (isUnavailable(failure)) {
          setAvailability('unavailable');
          return;
        }
        setAvailability('available');
        setError(`Combined history probe failed: ${failureMessage(failure)}`);
      });
    return () => {
      mounted = false;
    };
  }, []);

  const search = useCallback(async () => {
    const from = localHistoryTimeToInstant(fromTime);
    const to = localHistoryTimeToInstant(toTime);
    if (branch.trim().length === 0) {
      setError('The branch reachability boundary is required.');
      return;
    }
    if (fromTime.trim().length > 0 && from === null) {
      setError('The lower time bound is not a valid date and time.');
      return;
    }
    if (toTime.trim().length > 0 && to === null) {
      setError('The upper time bound is not a valid date and time.');
      return;
    }
    if (from !== null && to !== null && from > to) {
      setError('The lower time bound must not be after the upper time bound.');
      return;
    }

    setSearching(true);
    setError(null);
    try {
      const responses = await postJson<CombinedHistoryResponse[]>(COMBINED_HISTORY_URL, {
        generic: {
          text: query,
          authorEmail,
          pathText,
          from,
          to,
          limit,
        },
        semantic: {
          branch,
          workflowId,
          nodeId,
          nodeType,
          labelText,
          propertyKey,
          propertyValue,
        },
      });
      const nextHits = toHistoryHits(responses);
      setHits(nextHits);
      setStatus(
        nextHits.length === 0
          ? 'No branch-reachable checkpoints match all generic and semantic filters.'
          : `${nextHits.length} combined checkpoint${nextHits.length === 1 ? '' : 's'} found.`,
      );
    } catch (failure) {
      if (isUnavailable(failure)) {
        setAvailability('unavailable');
        return;
      }
      setError(failureMessage(failure));
    } finally {
      setSearching(false);
    }
  }, [
    authorEmail,
    branch,
    fromTime,
    labelText,
    limit,
    nodeId,
    nodeType,
    pathText,
    propertyKey,
    propertyValue,
    query,
    toTime,
    workflowId,
  ]);

  const rebuild = useCallback(async () => {
    setRebuilding(true);
    setError(null);
    try {
      const response = await postJson<RebuildResponse>(indexedHistoryRebuildUrl(branch), undefined);
      setStatus(
        response.indexedCommits === 0
          ? 'Indexes already match authoritative branch history.'
          : `Indexed ${response.indexedCommits} missing checkpoint${response.indexedCommits === 1 ? '' : 's'}.`,
      );
      await search();
    } catch (failure) {
      if (isUnavailable(failure)) {
        setAvailability('unavailable');
        return;
      }
      setError(failureMessage(failure));
    } finally {
      setRebuilding(false);
    }
  }, [branch, search]);

  const loadExactCommit = useCallback(async (commitId: string) => {
    const sessionId = activeSessionId();
    if (sessionId !== null) {
      setError(`Leave collaboration session ${sessionId} before loading historical state.`);
      return;
    }
    setLoadingCommitId(commitId);
    setError(null);
    try {
      await postJson<WorkflowProjection>('/workflow/load', { commitId });
      setStatus(`Loaded exact commit ${commitId.slice(0, 12)}. Refreshing the workbench…`);
      window.location.reload();
    } catch (failure) {
      setError(failureMessage(failure));
      setLoadingCommitId(null);
    }
  }, []);

  if (availability !== 'available') {
    return null;
  }

  return (
    <section className="indexed-history" data-testid="indexed-history-panel">
      <button
        aria-expanded={expanded}
        className="indexed-history__toggle"
        data-testid="indexed-history-toggle"
        onClick={() => setExpanded((current) => !current)}
        type="button"
      >
        {expanded ? 'Close version search' : 'Search version history'}
      </button>
      {expanded ? (
        <div className="indexed-history__drawer">
          <header className="indexed-history__header">
            <div>
              <h2>Combined version history</h2>
              <p>Apply branch and workflow semantics before generic ranking and the final limit.</p>
            </div>
            <button
              aria-label="Close indexed version history"
              className="indexed-history__close"
              onClick={() => setExpanded(false)}
              type="button"
            >
              ×
            </button>
          </header>

          <form
            className="indexed-history__search"
            onSubmit={(event) => {
              event.preventDefault();
              void search();
            }}
          >
            <label className="field">
              Full-text query
              <input
                data-testid="indexed-history-query"
                placeholder="gain, wingbeat, path or commit message"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
              />
            </label>
            <label className="field indexed-history__limit">
              Results
              <input
                data-testid="indexed-history-limit"
                max={200}
                min={1}
                type="number"
                value={limit}
                onChange={(event) => setLimit(normalizeHistorySearchLimit(event.target.value))}
              />
            </label>
            <div className="indexed-history__filters">
              <label className="field">
                Exact author email
                <input
                  autoComplete="off"
                  data-testid="indexed-history-author"
                  placeholder="researcher@example.org"
                  type="email"
                  value={authorEmail}
                  onChange={(event) => setAuthorEmail(event.target.value)}
                />
              </label>
              <label className="field">
                Changed-path terms
                <input
                  data-testid="indexed-history-path"
                  placeholder="workflows insect"
                  value={pathText}
                  onChange={(event) => setPathText(event.target.value)}
                />
              </label>
              <label className="field">
                From, inclusive
                <input
                  data-testid="indexed-history-from"
                  type="datetime-local"
                  value={fromTime}
                  onChange={(event) => setFromTime(event.target.value)}
                />
              </label>
              <label className="field">
                To, inclusive
                <input
                  data-testid="indexed-history-to"
                  type="datetime-local"
                  value={toTime}
                  onChange={(event) => setToTime(event.target.value)}
                />
              </label>
            </div>
            <div className="indexed-history__filters indexed-history__semantic-filters">
              <label className="field">
                Exact workflow ID
                <input
                  data-testid="indexed-history-workflow"
                  placeholder="workflow.insect-observer"
                  value={workflowId}
                  onChange={(event) => setWorkflowId(event.target.value)}
                />
              </label>
              <label className="field">
                Exact node ID
                <input
                  data-testid="indexed-history-node"
                  placeholder="node.classifier"
                  value={nodeId}
                  onChange={(event) => setNodeId(event.target.value)}
                />
              </label>
              <label className="field">
                Exact node type
                <input
                  data-testid="indexed-history-type"
                  placeholder="classifier"
                  value={nodeType}
                  onChange={(event) => setNodeType(event.target.value)}
                />
              </label>
              <label className="field">
                Workflow name or node label
                <input
                  data-testid="indexed-history-label"
                  placeholder="wingbeat"
                  value={labelText}
                  onChange={(event) => setLabelText(event.target.value)}
                />
              </label>
              <label className="field">
                Exact property key
                <input
                  data-testid="indexed-history-property-key"
                  placeholder="mode"
                  value={propertyKey}
                  onChange={(event) => setPropertyKey(event.target.value)}
                />
              </label>
              <label className="field">
                Exact property value
                <input
                  data-testid="indexed-history-property-value"
                  placeholder="safe"
                  value={propertyValue}
                  onChange={(event) => setPropertyValue(event.target.value)}
                />
              </label>
            </div>
            <button
              className="action-button"
              data-testid="indexed-history-search"
              disabled={searching || rebuilding}
              type="submit"
            >
              {searching ? 'Searching…' : 'Search combined indexes'}
            </button>
          </form>

          <div className="indexed-history__maintenance">
            <label className="field">
              Authoritative branch to search, rebuild and operate on
              <input
                data-testid="indexed-history-branch"
                value={branch}
                onChange={(event) => setBranch(event.target.value)}
              />
            </label>
            <button
              className="action-button"
              data-testid="indexed-history-rebuild"
              disabled={searching || rebuilding || branch.trim().length === 0}
              onClick={() => void rebuild()}
              type="button"
            >
              {rebuilding ? 'Rebuilding…' : 'Rebuild missing projections'}
            </button>
          </div>

          <p className="indexed-history__status" data-testid="indexed-history-status">
            {status}
          </p>
          {error === null ? null : (
            <p className="indexed-history__error" data-testid="indexed-history-error">
              {error}
            </p>
          )}

          <WorkflowHistoryCommands
            branch={branch}
            disabled={searching || rebuilding || loadingCommitId !== null}
            hits={hits}
            onError={setError}
            onStatus={setStatus}
          />

          <ol className="indexed-history__results" data-testid="indexed-history-results">
            {hits.map((hit) => {
              const paths = visibleChangedPaths(hit.changedPaths);
              return (
                <li className="indexed-history__hit" key={hit.commitId}>
                  <div className="indexed-history__hit-heading">
                    <strong>{historyHitLabel(hit)}</strong>
                    <code>{hit.commitId.slice(0, 12)}</code>
                  </div>
                  <p>
                    {hit.authorName || hit.authorEmail || 'Unknown author'} ·{' '}
                    {formattedTimestamp(hit.timestamp)}
                  </p>
                  <p className="indexed-history__semantic-summary">
                    {hit.semantics.workflowName} · {hit.semantics.workflowId} · node types:{' '}
                    {shortSemanticValues(hit.semantics.nodeTypes)}
                  </p>
                  {paths.length === 0 ? null : (
                    <ul>
                      {paths.map((path) => (
                        <li key={path}>{path}</li>
                      ))}
                    </ul>
                  )}
                  <button
                    className="action-button"
                    data-testid={`indexed-history-load-${hit.commitId}`}
                    disabled={loadingCommitId !== null}
                    onClick={() => void loadExactCommit(hit.commitId)}
                    type="button"
                  >
                    {loadingCommitId === hit.commitId
                      ? 'Loading exact version…'
                      : 'Load exact version'}
                  </button>
                </li>
              );
            })}
          </ol>
        </div>
      ) : null}
    </section>
  );
}
