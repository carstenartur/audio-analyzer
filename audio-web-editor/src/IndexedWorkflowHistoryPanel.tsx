import { useCallback, useEffect, useState } from 'react';

import { ApiError, getJson, postJson, type WorkflowProjection } from './api';
import {
  historyHitLabel,
  indexedHistoryRebuildUrl,
  indexedHistorySearchUrl,
  normalizeHistorySearchLimit,
  visibleChangedPaths,
} from './indexedHistorySearch.mjs';

interface IndexedHistoryHit {
  commitId: string;
  message: string;
  authorName: string | null;
  authorEmail: string | null;
  timestamp: string | null;
  changedPaths: string[];
}

interface RebuildResponse {
  indexedCommits: number;
}

type Availability = 'probing' | 'available' | 'unavailable';

const ACTIVE_SESSION_STORAGE_KEY = 'audio-analyzer.workflow.active-session';

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

/** Optional version-history UI, rendered only when the indexed search endpoint is available. */
export function IndexedWorkflowHistoryPanel() {
  const [availability, setAvailability] = useState<Availability>('probing');
  const [expanded, setExpanded] = useState(false);
  const [query, setQuery] = useState('');
  const [branch, setBranch] = useState('main');
  const [limit, setLimit] = useState(20);
  const [hits, setHits] = useState<IndexedHistoryHit[]>([]);
  const [searching, setSearching] = useState(false);
  const [rebuilding, setRebuilding] = useState(false);
  const [loadingCommitId, setLoadingCommitId] = useState<string | null>(null);
  const [status, setStatus] = useState('Derived index ready. Git remains authoritative.');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let mounted = true;
    void getJson<IndexedHistoryHit[]>(indexedHistorySearchUrl('', 1))
      .then((initialHits) => {
        if (!mounted) {
          return;
        }
        setHits(initialHits);
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
        setError(`History index probe failed: ${failureMessage(failure)}`);
      });
    return () => {
      mounted = false;
    };
  }, []);

  const search = useCallback(async () => {
    setSearching(true);
    setError(null);
    try {
      const nextHits = await getJson<IndexedHistoryHit[]>(indexedHistorySearchUrl(query, limit));
      setHits(nextHits);
      setStatus(
        nextHits.length === 0
          ? 'No indexed checkpoints match this query.'
          : `${nextHits.length} indexed checkpoint${nextHits.length === 1 ? '' : 's'} found.`,
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
  }, [limit, query]);

  const rebuild = useCallback(async () => {
    setRebuilding(true);
    setError(null);
    try {
      const response = await postJson<RebuildResponse>(indexedHistoryRebuildUrl(branch), undefined);
      setStatus(
        response.indexedCommits === 0
          ? 'Index already matches authoritative branch history.'
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
              <h2>Indexed version history</h2>
              <p>Search commit messages, changed paths and deterministic workflow DSL content.</p>
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
            <button
              className="action-button"
              data-testid="indexed-history-search"
              disabled={searching || rebuilding}
              type="submit"
            >
              {searching ? 'Searching…' : 'Search index'}
            </button>
          </form>

          <div className="indexed-history__maintenance">
            <label className="field">
              Authoritative branch
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
                    {loadingCommitId === hit.commitId ? 'Loading exact version…' : 'Load exact version'}
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
