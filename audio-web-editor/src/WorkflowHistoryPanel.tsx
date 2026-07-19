import { useCallback, useEffect, useMemo, useState } from 'react';

import type {
  BlockingOperationResponse,
  HistoryOperationResponse,
  RedoPreviewResponse,
  UndoPreviewResponse,
} from './api';
import {
  historyControlPolicy,
  historyModeExplanation,
  historyShortcutAction,
} from './historyControlsState.mjs';
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

function message(failure: unknown): string {
  return failure instanceof Error ? failure.message : String(failure);
}

function timestamp(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString();
}

function actorLabel(collaboration: WorkflowSessionController, actorId: string): string {
  const participant = collaboration.participants.find((candidate) => candidate.actorId === actorId);
  if (participant !== undefined) {
    return `${participant.displayName} (${actorId})`;
  }
  if (collaboration.session?.owner.actorId === actorId) {
    return `${collaboration.session.owner.displayName} (${actorId})`;
  }
  return actorId;
}

function isEditableTarget(target: EventTarget | null): boolean {
  return (
    target instanceof HTMLElement &&
    (target.isContentEditable ||
      target instanceof HTMLInputElement ||
      target instanceof HTMLTextAreaElement ||
      target instanceof HTMLSelectElement)
  );
}

function OperationFacts({
  collaboration,
  operation,
}: {
  collaboration: WorkflowSessionController;
  operation: HistoryOperationResponse;
}) {
  return (
    <dl className="history-facts">
      <div>
        <dt>Operation</dt>
        <dd>{operation.operationType}</dd>
      </div>
      <div>
        <dt>Actor</dt>
        <dd>{actorLabel(collaboration, operation.actorId)}</dd>
      </div>
      <div>
        <dt>Accepted</dt>
        <dd>
          <time className="history-timestamp" dateTime={operation.occurredAt}>
            {timestamp(operation.occurredAt)}
          </time>
        </dd>
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
            <code>{blocker.operationId}</code> by <code>{blocker.actorId}</code>
            <div>Objects: {blocker.conflictingObjectIds.join(', ')}</div>
          </li>
        ))}
      </ul>
    </div>
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
  const [localMessage, setLocalMessage] = useState<string | null>(null);

  const capabilities = history.capabilities;
  const mode = collaboration.session?.mode ?? capabilities?.mode ?? null;
  const policy = useMemo(
    () =>
      historyControlPolicy({
        capabilities,
        connectionState: collaboration.connectionState,
        historyStatus: history.status,
      }),
    [capabilities, collaboration.connectionState, history.status],
  );
  const activeTargets = useMemo(
    () => history.operations.filter((operation) => operation.activeUndoTarget),
    [history.operations],
  );
  const selectedTarget =
    activeTargets.find((operation) => operation.operationId === selectedTargetId) ?? null;
  const commandBusy = actionPending || policy.busy || history.status === 'uncertain';

  useEffect(() => {
    if (
      mode !== 'SHARED_SESSION_SHARED_UNDO' ||
      (selectedTargetId !== null &&
        !activeTargets.some((operation) => operation.operationId === selectedTargetId))
    ) {
      setSelectedTargetId(null);
    }
  }, [activeTargets, mode, selectedTargetId]);

  const prepareUndo = useCallback(
    async (targetOperationId?: string) => {
      const target =
        targetOperationId ?? capabilities?.personalUndo?.operation.operationId ?? null;
      if (target === null) {
        setLocalMessage('No current undo target is reported by the server.');
        return;
      }
      setActionPending(true);
      setSharedAcknowledged(false);
      setLocalMessage(null);
      try {
        setPreview({ kind: 'UNDO', value: await history.previewUndo(target) });
      } catch (failure) {
        setLocalMessage(message(failure));
        await history.reload('undo preview rejection').catch(() => undefined);
      } finally {
        setActionPending(false);
      }
    },
    [capabilities?.personalUndo?.operation.operationId, history],
  );

  const prepareRedo = useCallback(async () => {
    const target = capabilities?.redo?.operation.operationId ?? null;
    if (target === null) {
      setLocalMessage('No current redo target is reported by the server.');
      return;
    }
    setActionPending(true);
    setSharedAcknowledged(false);
    setLocalMessage(null);
    try {
      setPreview({ kind: 'REDO', value: await history.previewRedo(target) });
    } catch (failure) {
      setLocalMessage(message(failure));
      await history.reload('redo preview rejection').catch(() => undefined);
    } finally {
      setActionPending(false);
    }
  }, [capabilities?.redo?.operation.operationId, history]);

  const confirm = useCallback(async () => {
    if (preview === null || !preview.value.safe || commandBusy) {
      return;
    }
    if (mode === 'SHARED_SESSION_SHARED_UNDO' && preview.kind === 'UNDO' && !sharedAcknowledged) {
      return;
    }
    setActionPending(true);
    setLocalMessage(null);
    try {
      if (preview.kind === 'UNDO') {
        await history.executeUndo(preview.value);
      } else {
        await history.executeRedo(preview.value);
      }
      setPreview(null);
    } catch (failure) {
      setPreview(null);
      setLocalMessage(message(failure));
    } finally {
      setActionPending(false);
    }
  }, [commandBusy, history, mode, preview, sharedAcknowledged]);

  useEffect(() => {
    const handleShortcut = (event: KeyboardEvent) => {
      if (
        isEditableTarget(event.target) ||
        mode === null ||
        capabilities === null ||
        commandBusy ||
        preview !== null ||
        !(event.ctrlKey || event.metaKey)
      ) {
        return;
      }
      const key = event.key.toLowerCase();
      const redoShortcut = (key === 'z' && event.shiftKey) || key === 'y';
      if (key !== 'z' && key !== 'y') {
        return;
      }
      const action = historyShortcutAction({
        mode,
        shiftKey: redoShortcut,
        personalUndoEnabled: policy.personalUndoEnabled,
        redoEnabled: policy.redoEnabled,
        sharedTargetSelected: selectedTargetId !== null,
      });
      if (action === 'none') {
        return;
      }
      event.preventDefault();
      if (action === 'personal-undo') {
        void prepareUndo();
      } else if (action === 'redo') {
        void prepareRedo();
      } else if (action === 'shared-preview' && selectedTargetId !== null) {
        void prepareUndo(selectedTargetId);
      } else {
        setLocalMessage('Select an active shared operation before using the undo shortcut.');
      }
    };
    window.addEventListener('keydown', handleShortcut);
    return () => window.removeEventListener('keydown', handleShortcut);
  }, [
    capabilities,
    commandBusy,
    mode,
    policy.personalUndoEnabled,
    policy.redoEnabled,
    prepareRedo,
    prepareUndo,
    preview,
    selectedTargetId,
  ]);

  if (mode === null) {
    return null;
  }

  const problemBlockers = history.problem?.blockingOperations ?? [];
  const previewBlockers = preview?.value.blockingOperations ?? [];

  return (
    <section className="history-panel" data-testid="semantic-history-panel">
      <div className="history-panel__heading">
        <h3>Semantic history</h3>
        <button
          className="action-button action-button--secondary"
          data-testid="history-reload-button"
          disabled={commandBusy || !collaboration.active}
          onClick={() => void history.reload('manual history reload').catch((failure: unknown) => setLocalMessage(message(failure)))}
          type="button"
        >
          Reload
        </button>
      </div>

      <p className="help-text" data-testid="history-mode-explanation">
        {historyModeExplanation(mode)}
      </p>

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
            {policy.personalUndoVisible ? (
              <button
                className="action-button"
                data-testid="personal-undo-preview-button"
                disabled={!policy.personalUndoEnabled || actionPending}
                onClick={() => void prepareUndo()}
                title={policy.personalUndoReason}
                type="button"
              >
                Undo
              </button>
            ) : null}
            {policy.redoVisible ? (
              <button
                className="action-button"
                data-testid="redo-preview-button"
                disabled={!policy.redoEnabled || actionPending}
                onClick={() => void prepareRedo()}
                title={policy.redoReason}
                type="button"
              >
                Redo
              </button>
            ) : null}
          </div>
          <p className="help-text">Keyboard: Ctrl/⌘+Z · Ctrl/⌘+Shift+Z or Ctrl/⌘+Y</p>
        </>
      )}

      {capabilities?.personalUndo != null && !capabilities.personalUndo.available ? (
        <div className="history-warning" data-testid="personal-undo-unavailable">
          <strong>Undo unavailable: {capabilities.personalUndo.status}</strong>
          <p>{policy.personalUndoReason}</p>
          <BlockingList blockers={capabilities.personalUndo.blockingOperations} />
        </div>
      ) : null}
      {capabilities?.redo != null && !capabilities.redo.available ? (
        <div className="history-warning" data-testid="redo-unavailable">
          <strong>Redo unavailable: {capabilities.redo.status}</strong>
          <p>{policy.redoReason}</p>
          <BlockingList blockers={capabilities.redo.blockingOperations} />
        </div>
      ) : null}

      {policy.sharedUndoVisible ? (
        <div className="history-browser" data-testid="shared-history-browser">
          <h4>Shared undo targets</h4>
          {activeTargets.length === 0 ? (
            <p className="help-text">No active shared target is currently loaded.</p>
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
                      <strong>{operation.operationType}</strong> ·{' '}
                      {actorLabel(collaboration, operation.actorId)} ·{' '}
              <time className="history-timestamp" dateTime={operation.occurredAt}>
                {timestamp(operation.occurredAt)}
              </time>
                      {!operation.reconstructible ? ' · legacy' : ''}
                    </span>
                  </label>
                </li>
              ))}
            </ul>
          )}
          {selectedTarget === null ? null : (
            <OperationFacts collaboration={collaboration} operation={selectedTarget} />
          )}
          <div className="history-actions">
            <button
              className="action-button"
              data-testid="shared-undo-preview-button"
              disabled={
                !policy.sharedUndoEnabled ||
                selectedTarget === null ||
                !selectedTarget.reconstructible ||
                actionPending
              }
              onClick={() =>
                selectedTarget === null ? undefined : void prepareUndo(selectedTarget.operationId)
              }
              type="button"
            >
              Preview shared undo
            </button>
            {history.nextBeforeRevision === null ? null : (
              <button
                className="action-button action-button--secondary"
                data-testid="history-load-more-button"
                disabled={commandBusy}
                onClick={() => void history.loadMore().catch((failure: unknown) => setLocalMessage(message(failure)))}
                type="button"
              >
                Load older operations
              </button>
            )}
          </div>
        </div>
      ) : null}

      {history.status === 'uncertain' && history.pendingCommand !== null ? (
        <div className="history-warning" data-testid="history-uncertain-command">
          <strong>Command result is uncertain.</strong>
          <p>
            Retry reuses <code>{history.pendingCommand.commandId}</code>; no replacement identity is
            generated.
          </p>
          <div className="history-actions">
            <button
              className="action-button"
              data-testid="history-retry-button"
              disabled={actionPending || collaboration.connectionState !== 'live'}
              onClick={() => void history.retryPending().catch((failure: unknown) => setLocalMessage(message(failure)))}
              type="button"
            >
              Retry same command
            </button>
            <button
              className="action-button action-button--secondary"
              onClick={() =>
                void history.reload('uncertain command reconciliation').catch((failure: unknown) => setLocalMessage(message(failure)))
              }
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
      {localMessage === null ? null : (
        <p className="workbench__error" data-testid="history-local-message">
          {localMessage}
        </p>
      )}

      {preview === null ? null : (
        <div className="history-dialog-backdrop">
          <section
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
                <dd>{actorLabel(collaboration, preview.value.targetActorId)}</dd>
              </div>
              <div>
                <dt>Accepted</dt>
                <dd>
            <time className="history-timestamp" dateTime={preview.value.targetOccurredAt}>
              {timestamp(preview.value.targetOccurredAt)}
            </time>
          </dd>
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
          </section>
        </div>
      )}
    </section>
  );
}
