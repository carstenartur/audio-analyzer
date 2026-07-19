import { useCallback, useEffect, useMemo, useState } from 'react';

import type {
  BlockingOperationResponse,
  HistoryActionResponse,
  HistoryOperationResponse,
  RedoPreviewResponse,
  UndoPreviewResponse,
} from './api';
import type { WorkflowHistoryController } from './useWorkflowHistory';
import type { WorkflowSessionController } from './useWorkflowSession';
import './workflowHistory.css';

interface WorkflowHistoryPanelProps {
  collaboration: WorkflowSessionController;
  history: WorkflowHistoryController;
}

type Preview =
  | { kind: 'UNDO'; value: UndoPreviewResponse }
  | { kind: 'REDO'; value: RedoPreviewResponse };

function modeExplanation(mode: string): string {
  switch (mode) {
    case 'PRIVATE_WORKSPACE':
      return 'Only the owner participates. Undo and redo use the owner’s durable semantic history.';
    case 'SHARED_SESSION_PERSONAL_UNDO':
      return 'Each participant can undo only their own latest active operation; dependent edits may block it.';
    case 'SHARED_SESSION_SHARED_UNDO':
      return 'Select an active operation. A fresh server preview and explicit shared confirmation are required.';
    default:
      return 'The server controls semantic undo and redo for this immutable session mode.';
  }
}

function timestamp(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString();
}

function actionReason(action: HistoryActionResponse | null, label: string): string | null {
  if (action === null) {
    return `No ${label} target is currently reported by the server.`;
  }
  if (action.status === 'NOT_RECONSTRUCTIBLE') {
    return 'This legacy operation is visible but has no reconstructible semantic body.';
  }
  if (action.status === 'BLOCKED') {
    return `${action.blockingOperations.length} later operation(s) currently block this action.`;
  }
  return null;
}

function blockersFromProblem(problem: WorkflowHistoryController['problem']): BlockingOperationResponse[] {
  const value = problem?.blockingOperations;
  if (!Array.isArray(value)) {
    return [];
  }
  return value.filter(
    (candidate): candidate is BlockingOperationResponse =>
      typeof candidate === 'object' &&
      candidate !== null &&
      typeof (candidate as BlockingOperationResponse).operationId === 'string' &&
      typeof (candidate as BlockingOperationResponse).actorId === 'string' &&
      Array.isArray((candidate as BlockingOperationResponse).conflictingObjectIds),
  );
}

function OperationFacts({ operation }: { operation: HistoryOperationResponse }) {
  return (
    <dl className="history-facts">
      <div>
        <dt>Operation</dt>
        <dd>{operation.operationType}</dd>
      </div>
      <div>
        <dt>Actor</dt>
        <dd>{operation.actorId}</dd>
      </div>
      <div>
        <dt>Accepted</dt>
        <dd>{timestamp(operation.occurredAt)}</dd>
      </div>
      <div>
        <dt>Revision</dt>
        <dd>{operation.revision}</dd>
      </div>
      <div>
        <dt>Objects</dt>
        <dd>{operation.affectedObjectIds.join(', ') || 'None reported'}</dd>
      </div>
      <div>
        <dt>Body</dt>
        <dd>{operation.reconstructible ? 'Reconstructible' : 'Legacy identity only'}</dd>
      </div>
    </dl>
  );
}

function BlockingList({ blockers }: { blockers: BlockingOperationResponse[] }) {
  if (blockers.length === 0) {
    return null;
  }
  return (
    <div className="history-blockers" data-testid="history-blockers">
      <strong>Blocking operations</strong>
      <ul>
        {blockers.map((blocker) => (
          <li key={blocker.operationId}>
            <code>{blocker.operationId}</code> by {blocker.actorId}: {' '}
            {blocker.conflictingObjectIds.join(', ')}
          </li>
        ))}
      </ul>
    </div>
  );
}

function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false;
  }
  return (
    target.isContentEditable ||
    target instanceof HTMLInputElement ||
    target instanceof HTMLTextAreaElement ||
    target instanceof HTMLSelectElement
  );
}

export function WorkflowHistoryPanel({
  collaboration,
  history,
}: WorkflowHistoryPanelProps) {
  const [selectedTargetId, setSelectedTargetId] = useState<string | null>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [sharedAcknowledged, setSharedAcknowledged] = useState(false);
  const [actionPending, setActionPending] = useState(false);

  const capabilities = history.capabilities;
  const mode = collaboration.session?.mode ?? null;
  const activeTargets = useMemo(
    () => history.operations.filter((operation) => operation.activeUndoTarget),
    [history.operations],
  );
  const selectedTarget =
    history.operations.find((operation) => operation.operationId === selectedTargetId) ?? null;
  const commandBusy =
    actionPending || history.status === 'pending' || history.status === 'uncertain';
  const connected = collaboration.connectionState === 'live';

  useEffect(() => {
    if (mode !== 'SHARED_SESSION_SHARED_UNDO') {
      setSelectedTargetId(null);
      return;
    }
    setSelectedTargetId((current) =>
      activeTargets.some((operation) => operation.operationId === current)
        ? current
        : (activeTargets[0]?.operationId ?? null),
    );
  }, [activeTargets, mode]);

  const prepareUndo = useCallback(
    async (targetOperationId?: string) => {
      const target =
        targetOperationId ?? capabilities?.personalUndo?.operation.operationId ?? null;
      if (target === null) {
        return;
      }
      setActionPending(true);
      setSharedAcknowledged(false);
      try {
        setPreview({ kind: 'UNDO', value: await history.previewUndo(target) });
      } finally {
        setActionPending(false);
      }
    },
    [capabilities?.personalUndo?.operation.operationId, history],
  );

  const prepareRedo = useCallback(async () => {
    const target = capabilities?.redo?.operation.operationId ?? null;
    if (target === null) {
      return;
    }
    setActionPending(true);
    setSharedAcknowledged(false);
    try {
      setPreview({ kind: 'REDO', value: await history.previewRedo(target) });
    } finally {
      setActionPending(false);
    }
  }, [capabilities?.redo?.operation.operationId, history]);

  useEffect(() => {
    const handleShortcut = (event: KeyboardEvent) => {
      if (
        isEditableTarget(event.target) ||
        !connected ||
        commandBusy ||
        preview !== null ||
        capabilities === null
      ) {
        return;
      }
      const modifier = event.ctrlKey || event.metaKey;
      if (!modifier) {
        return;
      }
      const redoShortcut =
        (event.key.toLowerCase() === 'z' && event.shiftKey) ||
        event.key.toLowerCase() === 'y';
      if (redoShortcut && capabilities.redo !== null) {
        event.preventDefault();
        void prepareRedo();
        return;
      }
      if (event.key.toLowerCase() !== 'z' || event.shiftKey) {
        return;
      }
      const target =
        mode === 'SHARED_SESSION_SHARED_UNDO'
          ? selectedTargetId
          : capabilities.personalUndo?.operation.operationId ?? null;
      if (target !== null) {
        event.preventDefault();
        void prepareUndo(target);
      }
    };
    window.addEventListener('keydown', handleShortcut);
    return () => window.removeEventListener('keydown', handleShortcut);
  }, [
    capabilities,
    commandBusy,
    connected,
    mode,
    prepareRedo,
    prepareUndo,
    preview,
    selectedTargetId,
  ]);

  const confirm = async () => {
    if (preview === null || !preview.value.safe || commandBusy) {
      return;
    }
    if (mode === 'SHARED_SESSION_SHARED_UNDO' && !sharedAcknowledged) {
      return;
    }
    setActionPending(true);
    try {
      if (preview.kind === 'UNDO') {
        await history.executeUndo(preview.value);
      } else {
        await history.executeRedo(preview.value);
      }
      setPreview(null);
    } finally {
      setActionPending(false);
    }
  };

  const problemBlockers = blockersFromProblem(history.problem);
  const previewBlockers = preview?.value.blockingOperations ?? [];
  const personalUndoReason = actionReason(capabilities?.personalUndo ?? null, 'undo');
  const redoReason = actionReason(capabilities?.redo ?? null, 'redo');

  return (
    <section className="history-panel" data-testid="semantic-history-panel">
      <div className="history-panel__heading">
        <h3>Semantic history</h3>
        <button
          className="action-button action-button--secondary"
          data-testid="history-reload-button"
          disabled={commandBusy || !collaboration.active}
          onClick={() => void history.reload('manual history reload')}
          type="button"
        >
          Reload
        </button>
      </div>

      {mode === null ? null : (
        <p className="help-text" data-testid="history-mode-explanation">
          {modeExplanation(mode)}
        </p>
      )}

      {capabilities === null ? (
        <p className="help-text" data-testid="history-loading-state">
          {history.status === 'error' ? 'History is unavailable.' : 'Loading durable history…'}
        </p>
      ) : (
        <>
          <p className="history-revision" data-testid="history-capability-revision">
            Capability revision {capabilities.revision}
          </p>
          <div className="history-actions">
            {mode === 'SHARED_SESSION_SHARED_UNDO' ? (
              <button
                className="action-button"
                data-testid="shared-undo-preview-button"
                disabled={
                  !connected ||
                  commandBusy ||
                  selectedTarget === null ||
                  !selectedTarget.reconstructible
                }
                onClick={() =>
                  selectedTarget === null ? undefined : void prepareUndo(selectedTarget.operationId)
                }
                type="button"
              >
                Preview shared undo
              </button>
            ) : (
              <button
                className="action-button"
                data-testid="personal-undo-preview-button"
                disabled={
                  !connected ||
                  commandBusy ||
                  capabilities.personalUndo === null ||
                  capabilities.personalUndo.status === 'NOT_RECONSTRUCTIBLE'
                }
                onClick={() => void prepareUndo()}
                title={personalUndoReason ?? undefined}
                type="button"
              >
                {capabilities.personalUndo?.status === 'BLOCKED' ? 'Review blocked undo' : 'Undo'}
              </button>
            )}
            <button
              className="action-button"
              data-testid="redo-preview-button"
              disabled={
                !connected ||
                commandBusy ||
                capabilities.redo === null ||
                capabilities.redo.status === 'NOT_RECONSTRUCTIBLE'
              }
              onClick={() => void prepareRedo()}
              title={redoReason ?? undefined}
              type="button"
            >
              {capabilities.redo?.status === 'BLOCKED' ? 'Review blocked redo' : 'Redo'}
            </button>
          </div>
          <p className="help-text">Keyboard: Ctrl/⌘+Z · Ctrl/⌘+Shift+Z or Ctrl/⌘+Y</p>
        </>
      )}

      {mode === 'SHARED_SESSION_SHARED_UNDO' ? (
        <div className="history-browser" data-testid="shared-history-browser">
          <h4>Shared undo targets</h4>
          {activeTargets.length === 0 ? (
            <p className="help-text">No active reconstructible target is currently loaded.</p>
          ) : (
            <ul className="history-target-list">
              {activeTargets.map((operation) => (
                <li key={operation.operationId}>
                  <label className="history-target">
                    <input
                      checked={selectedTargetId === operation.operationId}
                      data-testid={`history-target-${operation.operationId}`}
                      name="shared-history-target"
                      onChange={() => setSelectedTargetId(operation.operationId)}
                      type="radio"
                    />
                    <span>
                      <strong>{operation.operationType}</strong> · {operation.actorId} · revision {' '}
                      {operation.revision}
                      {!operation.reconstructible ? ' · legacy' : ''}
                    </span>
                  </label>
                </li>
              ))}
            </ul>
          )}
          {selectedTarget === null ? null : <OperationFacts operation={selectedTarget} />}
          {history.nextBeforeRevision === null ? null : (
            <button
              className="action-button action-button--secondary"
              data-testid="history-load-more-button"
              disabled={commandBusy}
              onClick={() => void history.loadMore()}
              type="button"
            >
              Load older operations
            </button>
          )}
        </div>
      ) : null}

      {history.status === 'uncertain' && history.pendingCommand !== null ? (
        <div className="history-warning" data-testid="history-uncertain-command">
          <strong>Command result is uncertain.</strong>
          <p>
            Retry reuses <code>{history.pendingCommand.commandId}</code>; a new command ID will not be
            generated.
          </p>
          <div className="history-actions">
            <button
              className="action-button"
              data-testid="history-retry-button"
              disabled={actionPending}
              onClick={() => void history.retryPending()}
              type="button"
            >
              Retry same command
            </button>
            <button
              className="action-button action-button--secondary"
              onClick={() => void history.reload('uncertain command reconciliation')}
              type="button"
            >
              Reload history
            </button>
          </div>
        </div>
      ) : null}

      {history.problem === null ? null : (
        <div className="history-error" data-testid="history-problem">
          <strong>{history.problem.title ?? history.problem.code ?? 'History request failed'}</strong>
          <p>{history.problem.detail ?? 'The server rejected the history request.'}</p>
          <BlockingList blockers={problemBlockers} />
          <button
            className="action-button action-button--secondary"
            onClick={history.clearProblem}
            type="button"
          >
            Dismiss
          </button>
        </div>
      )}

      {preview === null ? null : (
        <div
          aria-labelledby="history-preview-title"
          aria-modal="true"
          className="history-dialog"
          data-testid="history-preview-dialog"
          role="dialog"
        >
          <h4 id="history-preview-title">
            Confirm {preview.kind === 'UNDO' ? 'undo' : 'redo'}
          </h4>
          <p>
            Server preview at revision {preview.value.revision}:{' '}
            {preview.value.safe ? 'safe to apply.' : 'currently blocked.'}
          </p>
          <dl className="history-facts">
            <div>
              <dt>Operation</dt>
              <dd>{preview.value.operationType}</dd>
            </div>
            <div>
              <dt>Actor</dt>
              <dd>{preview.value.targetActorId}</dd>
            </div>
            <div>
              <dt>Accepted</dt>
              <dd>{timestamp(preview.value.targetOccurredAt)}</dd>
            </div>
            <div>
              <dt>Objects</dt>
              <dd>{preview.value.affectedObjectIds.join(', ') || 'None reported'}</dd>
            </div>
          </dl>
          <BlockingList blockers={previewBlockers} />
          {mode === 'SHARED_SESSION_SHARED_UNDO' && preview.kind === 'UNDO' ? (
            <label className="history-shared-confirmation">
              <input
                checked={sharedAcknowledged}
                data-testid="shared-undo-confirmation"
                onChange={(event) => setSharedAcknowledged(event.target.checked)}
                type="checkbox"
              />
              I understand that this changes the shared canonical workflow for all participants.
            </label>
          ) : null}
          <div className="history-actions">
            <button
              className="action-button"
              data-testid="history-confirm-button"
              disabled={
                commandBusy ||
                !preview.value.safe ||
                (mode === 'SHARED_SESSION_SHARED_UNDO' &&
                  preview.kind === 'UNDO' &&
                  !sharedAcknowledged)
              }
              onClick={() => void confirm()}
              type="button"
            >
              Confirm {preview.kind === 'UNDO' ? 'undo' : 'redo'}
            </button>
            <button
              className="action-button action-button--secondary"
              data-testid="history-cancel-button"
              disabled={commandBusy}
              onClick={() => setPreview(null)}
              type="button"
            >
              Cancel
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
