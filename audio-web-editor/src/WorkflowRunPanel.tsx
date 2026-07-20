import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from 'react';

import { ApiError, type ApiProblem } from './api';
import {
  cancelWorkflowRun,
  inspectWorkflowRun,
  loadWorkflowRunResult,
  startWorkflowRun,
  type HistoricalWorkflowRunSource,
  type WorkflowRunCommand,
  type WorkflowRunSnapshot,
  type WorkflowRunState,
} from './workflowRunApi';
import {
  createLiveRunCommand,
  createStoredRunCommand,
  emptyWorkflowRunState,
  isTerminalRunState,
  reduceWorkflowRunState,
  runProblemMessages,
  runStartRequest,
} from './workflowRunState.mjs';

const POLL_INTERVAL_MILLIS = 750;

interface CurrentWorkflowRunSource {
  sessionId: string;
  revision: number;
  workflowId: string;
}

interface WorkflowRunPanelProps {
  currentSource: CurrentWorkflowRunSource | null;
  history: HistoricalWorkflowRunSource[];
  onError: (message: string | null) => void;
  onStatus: (message: string) => void;
}

function problemFor(failure: unknown): ApiProblem {
  if (failure instanceof ApiError) {
    return failure.problem ?? {
      status: failure.status,
      code: failure.code ?? undefined,
      detail: failure.message,
    };
  }
  return { detail: failure instanceof Error ? failure.message : String(failure) };
}

function commandId(): string {
  return `run-command-${crypto.randomUUID()}`;
}

function formatInstant(value: string | null): string {
  return value === null ? '—' : new Date(value).toLocaleString();
}

function sourceLabel(run: WorkflowRunSnapshot): string {
  if (run.source.kind === 'LIVE_SESSION') {
    return `${run.source.sessionId ?? 'unknown session'} @ revision ${run.source.semanticRevision ?? '?'}`;
  }
  return `commit ${run.source.commitId ?? 'unknown'}`;
}

function downloadResult(runId: string, result: unknown): void {
  const blob = new Blob([`${JSON.stringify(result, null, 2)}\n`], {
    type: 'application/json;charset=utf-8',
  });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `workflow-run-${runId}.json`;
  anchor.click();
  URL.revokeObjectURL(url);
}

export function WorkflowRunPanel({
  currentSource,
  history,
  onError,
  onStatus,
}: WorkflowRunPanelProps) {
  const [state, dispatch] = useReducer(
    reduceWorkflowRunState,
    undefined,
    emptyWorkflowRunState,
  ) as [WorkflowRunState, React.Dispatch<Record<string, unknown>>];
  const [selectedCommitId, setSelectedCommitId] = useState('');
  const resultRequests = useRef(new Set<string>());

  useEffect(() => {
    if (selectedCommitId !== '' && history.some((entry) => entry.commitId === selectedCommitId)) {
      return;
    }
    setSelectedCommitId(history[0]?.commitId ?? '');
  }, [history, selectedCommitId]);

  const selectedCommit = useMemo(
    () => history.find((entry) => entry.commitId === selectedCommitId) ?? null,
    [history, selectedCommitId],
  );

  const submitCommand = useCallback(
    async (command: WorkflowRunCommand, retry: boolean) => {
      if (!retry) {
        dispatch({ type: 'START_SUBMITTED', command });
      }
      onError(null);
      onStatus(retry ? 'Retrying immutable workflow run…' : 'Capturing immutable workflow run…');
      try {
        const run = await startWorkflowRun(runStartRequest(command));
        dispatch({ type: 'START_ACCEPTED', run });
        onStatus(`Run ${run.runId} accepted as ${run.mode}`);
      } catch (failure) {
        const problem = problemFor(failure);
        if (failure instanceof ApiError) {
          dispatch({ type: 'START_REJECTED', problem });
          onStatus('Workflow run preflight rejected');
        } else {
          dispatch({ type: 'START_UNCERTAIN', problem });
          onStatus('Workflow run transport outcome is uncertain; retry uses the same command id');
        }
        onError(runProblemMessages(problem).join(' · '));
      }
    },
    [onError, onStatus],
  );

  const startCurrent = useCallback(() => {
    if (currentSource === null) {
      return;
    }
    const command = createLiveRunCommand(
      commandId(),
      currentSource.sessionId,
      currentSource.revision,
    ) as WorkflowRunCommand;
    void submitCommand(command, false);
  }, [currentSource, submitCommand]);

  const startHistorical = useCallback(() => {
    if (selectedCommit === null) {
      return;
    }
    const command = createStoredRunCommand(commandId(), selectedCommit.commitId) as WorkflowRunCommand;
    void submitCommand(command, false);
  }, [selectedCommit, submitCommand]);

  const retryStart = useCallback(() => {
    if (state.command !== null) {
      void submitCommand(state.command, true);
    }
  }, [state.command, submitCommand]);

  const cancelRun = useCallback(async () => {
    if (state.run === null || isTerminalRunState(state.run.state)) {
      return;
    }
    try {
      const run = await cancelWorkflowRun(state.run.runId);
      dispatch({ type: 'SNAPSHOT_RECEIVED', run });
      onStatus(`Cancellation requested for ${run.runId}`);
    } catch (failure) {
      const problem = problemFor(failure);
      dispatch({ type: 'POLL_FAILED', problem });
      onError(runProblemMessages(problem).join(' · '));
    }
  }, [onError, onStatus, state.run]);

  useEffect(() => {
    const run = state.run;
    if (run === null || isTerminalRunState(run.state)) {
      return undefined;
    }
    let disposed = false;
    let timer: ReturnType<typeof setTimeout> | null = null;

    const poll = async () => {
      try {
        const incoming = await inspectWorkflowRun(run.runId);
        if (disposed) {
          return;
        }
        dispatch({ type: 'SNAPSHOT_RECEIVED', run: incoming });
        if (!isTerminalRunState(incoming.state)) {
          timer = setTimeout(() => void poll(), POLL_INTERVAL_MILLIS);
        }
      } catch (failure) {
        if (disposed) {
          return;
        }
        const problem = problemFor(failure);
        dispatch({ type: 'POLL_FAILED', problem });
        timer = setTimeout(() => void poll(), POLL_INTERVAL_MILLIS);
      }
    };

    timer = setTimeout(() => void poll(), POLL_INTERVAL_MILLIS);
    return () => {
      disposed = true;
      if (timer !== null) {
        clearTimeout(timer);
      }
    };
  }, [state.run]);

  useEffect(() => {
    const run = state.run;
    if (
      run === null ||
      !isTerminalRunState(run.state) ||
      state.result !== null ||
      resultRequests.current.has(run.runId)
    ) {
      return;
    }
    resultRequests.current.add(run.runId);
    void loadWorkflowRunResult(run.runId)
      .then((result) => {
        dispatch({ type: 'RESULT_LOADED', result });
        onStatus(`Run ${run.runId} finished as ${result.overallStatus}`);
      })
      .catch((failure: unknown) => {
        const problem = problemFor(failure);
        dispatch({ type: 'RESULT_UNAVAILABLE', problem });
        onError(runProblemMessages(problem).join(' · '));
      });
  }, [onError, onStatus, state.result, state.run]);

  const reset = useCallback(() => {
    dispatch({ type: 'RESET' });
    onError(null);
    onStatus('Ready to capture another immutable workflow run');
  }, [onError, onStatus]);

  const busy = state.phase === 'starting' || state.phase === 'active';
  const problemMessages = runProblemMessages(state.problem);

  return (
    <section className="workflow-run" data-testid="workflow-run-panel">
      <h3>Immutable run</h3>
      <p className="help-text">
        The server captures an exact revision or commit. Editing remains enabled and affects only
        future runs.
      </p>

      <div className="workflow-run__source" data-testid="run-current-source">
        <strong>Current session</strong>
        {currentSource === null ? (
          <span className="help-text">Join a collaboration session to run its displayed revision.</span>
        ) : (
          <span>
            {currentSource.workflowId} · {currentSource.sessionId} · revision {currentSource.revision}
          </span>
        )}
        <button
          className="action-button"
          data-testid="run-current-revision"
          disabled={currentSource === null || busy}
          onClick={startCurrent}
          type="button"
        >
          Run current revision
        </button>
      </div>

      <label className="field">
        Historical commit
        <select
          data-testid="run-history-commit"
          disabled={history.length === 0 || busy}
          value={selectedCommitId}
          onChange={(event) => setSelectedCommitId(event.target.value)}
        >
          {history.length === 0 ? <option value="">Refresh checkpoint history first</option> : null}
          {history.map((entry) => (
            <option key={entry.commitId} value={entry.commitId}>
              {entry.message || entry.commitId.slice(0, 10)} · {entry.commitId.slice(0, 10)}
            </option>
          ))}
        </select>
      </label>
      {selectedCommit === null ? null : (
        <p className="help-text" data-testid="run-history-source-summary">
          {selectedCommit.workflowId} · {selectedCommit.commitId} · {formatInstant(selectedCommit.timestamp)}
        </p>
      )}
      <button
        className="action-button"
        data-testid="run-selected-commit"
        disabled={selectedCommit === null || busy}
        onClick={startHistorical}
        type="button"
      >
        Run selected commit
      </button>

      {state.phase === 'uncertain' ? (
        <button className="action-button" data-testid="run-retry-start" onClick={retryStart} type="button">
          Retry same start command
        </button>
      ) : null}

      {state.run === null ? null : (
        <div className="workflow-run__record" data-testid="run-record">
          <div className="workflow-run__headline">
            <strong>{state.run.runId}</strong>
            <span
              className={`workflow-run__mode workflow-run__mode--${state.run.mode.toLowerCase()}`}
              data-testid="run-mode"
            >
              {state.run.mode}
            </span>
            <span data-testid="run-state">{state.run.state}</span>
          </div>
          <dl className="workflow-run__facts">
            <div>
              <dt>Source</dt>
              <dd>{sourceLabel(state.run)}</dd>
            </div>
            <div>
              <dt>Workflow</dt>
              <dd>{state.run.workflowId}</dd>
            </div>
            <div>
              <dt>Fingerprint</dt>
              <dd data-testid="run-fingerprint">{state.run.fingerprint}</dd>
            </div>
            <div>
              <dt>Captured</dt>
              <dd>{formatInstant(state.run.capturedAt)}</dd>
            </div>
            <div>
              <dt>Finished</dt>
              <dd>{formatInstant(state.run.finishedAt)}</dd>
            </div>
          </dl>
          <progress
            data-testid="run-progress"
            max={100}
            value={state.run.progressPercent}
          />
          <p className="help-text">{state.run.progressPercent}% · {state.run.statusMessage}</p>
          {!isTerminalRunState(state.run.state) ? (
            <button
              className="action-button"
              data-testid="run-cancel"
              onClick={() => void cancelRun()}
              type="button"
            >
              Cancel run
            </button>
          ) : null}
          {state.run.violations.length === 0 ? null : (
            <ul className="validation-list" data-testid="run-violations">
              {state.run.violations.map((violation) => (
                <li key={`${violation.code}:${violation.nodeId ?? ''}:${violation.message}`}>
                  {violation.code}{violation.nodeId === null ? '' : ` [${violation.nodeId}]`}: {violation.message}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {problemMessages.length === 0 ? null : (
        <ul className="validation-list" data-testid="run-problem">
          {problemMessages.map((message) => (
            <li key={message}>{message}</li>
          ))}
        </ul>
      )}

      {state.result === null ? null : (
        <div className="workflow-run__result" data-testid="run-result">
          <strong>Result: {state.result.overallStatus}</strong>
          <ul className="workflow-run__node-statuses">
            {Object.entries(state.result.nodeStatuses).map(([nodeId, nodeState]) => (
              <li key={nodeId}>
                {nodeId}: {nodeState}
              </li>
            ))}
          </ul>
          <dl className="workflow-run__artifacts">
            {Object.entries(state.result.artifacts).map(([key, value]) => (
              <div key={key}>
                <dt>{key}</dt>
                <dd>{value}</dd>
              </div>
            ))}
          </dl>
          <button
            className="action-button"
            data-testid="run-export-result"
            onClick={() => downloadResult(state.result?.run.runId ?? 'unknown', state.result)}
            type="button"
          >
            Export server result JSON
          </button>
        </div>
      )}

      {state.run !== null && isTerminalRunState(state.run.state) ? (
        <button className="action-button" data-testid="run-reset" onClick={reset} type="button">
          Prepare another run
        </button>
      ) : null}
    </section>
  );
}
