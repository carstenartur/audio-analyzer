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

interface HistoryControlsProps {
  collaboration: WorkflowSessionController;
  history: WorkflowHistoryController;
}

type PreviewDialog =
  | { kind: 'UNDO'; preview: UndoPreviewResponse }
  | { kind: 'REDO'; preview: RedoPreviewResponse };

function message(failure: unknown): string {
  return failure instanceof Error ? failure.message : String(failure);
}

function formattedTime(value: string): string {
  const timestamp = new Date(value);
  return Number.isNaN(timestamp.getTime()) ? value : timestamp.toLocaleString();
}

function actorLabel(controller: WorkflowSessionController, actorId: string): string {
  const participant = controller.participants.find((candidate) => candidate.actorId === actorId);
  if (participant !== undefined) {
    return `${participant.displayName} (${actorId})`;
  }
  if (controller.session?.owner.actorId === actorId) {
    return `${controller.session.owner.displayName} (${actorId})`;
  }
  return actorId;
}

function affectedObjects(operation: HistoryOperationResponse): string {
  return operation.affectedObjectIds.length === 0
    ? 'No semantic object ids reported'
    : operation.affectedObjectIds.join(', ');
}

function Blockers({ blockers }: { blockers: BlockingOperationResponse[] }) {
  if (blockers.length === 0) {
    return null;
  }
  return (
    <div className="history-conflicts" data-testid="history-blockers">
      <strong>Blocking operations</strong>
      <ul>
        {blockers.map((blocker) => (
          <li key={blocker.operationId}>
            <code>{blocker.operationId}</code> by <code>{blocker.actorId}</code>
            <div className="help-text">Objects: {blocker.conflictingObjectIds.join(', ')}</div>
          </li>
        ))}
      </ul>
    </div>
  );
}

function PreviewDialogView({
  dialog,
  pending,
  onCancel,
  onConfirm,
}: {
  dialog: PreviewDialog;
  pending: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const preview = dialog.preview;
  const targetOperationId =
    dialog.kind === 'UNDO' ? preview.targetOperationId : preview.targetUndoOperationId;
  return (
    <div className="history-dialog-backdrop">
      <section
        aria-labelledby="history-dialog-title"
        aria-modal="true"
        className="history-dialog"
        data-testid="history-confirmation-dialog"
        role="dialog"
      >
        <h3 id="history-dialog-title">
          Confirm semantic {dialog.kind === 'UNDO' ? 'undo' : 'redo'}
        </h3>
        <dl className="history-facts">
          <div>
            <dt>Target</dt>
            <dd>
              <code>{targetOperationId}</code>
            </dd>
          </div>
          <div>
            <dt>Operation</dt>
            <dd>{preview.operationType}</dd>
          </div>
          <div>
            <dt>Actor</dt>
            <dd>{preview.targetActorId}</dd>
          </div>
          <div>
            <dt>Timestamp</dt>
            <dd>{formattedTime(preview.targetOccurredAt)}</dd>
          </div>
          <div>
            <dt>Revision</dt>
            <dd>{preview.revision}</dd>
          </div>
          <div>
            <dt>Affected objects</dt>
            <dd>{preview.affectedObjectIds.join(', ') || 'None reported'}</dd>
          </div>
        </dl>
        <Blockers blockers={preview.blockingOperations} />
        {!preview.safe ? (
          <p className="workbench__error" data-testid="history-preview-unsafe">
            This semantic inverse is blocked and cannot be confirmed.
          </p>
        ) : (
          <p className="help-text">
            The server will revalidate this preview and the expected revision before append.
          </p>
        )}
        <div className="history-dialog__actions">
          <button className="action-button" disabled={pending} onClick={onCancel} type="button">
            Cancel
          </button>
          <button
            className="action-button"
            data-testid="confirm-history-command"
            disabled={!preview.safe || pending}
            onClick={onConfirm}
            type="button"
          >
            {pending ? 'Submitting…' : `Confirm ${dialog.kind.toLowerCase()}`}
          </button>
        </div>
      </section>
    </div>
  );
}

export function HistoryControls({ collaboration, history }: HistoryControlsProps) {
  const [selectedSharedTargetId, setSelectedSharedTargetId] = useState<string | null>(null);
  const [dialog, setDialog] = useState<PreviewDialog | null>(null);
  const [previewPending, setPreviewPending] = useState(false);
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
  const sharedTargets = useMemo(
    () => history.operations.filter((operation) => operation.activeUndoTarget),
    [history.operations],
  );

  useEffect(() => {
    if (
      selectedSharedTargetId !== null &&
      !sharedTargets.some((operation) => operation.operationId === selectedSharedTargetId)
    ) {
      setSelectedSharedTargetId(null);
    }
  }, [selectedSharedTargetId, sharedTargets]);

  const requestUndoPreview = useCallback(
    async (targetOperationId: string) => {
      setPreviewPending(true);
      setLocalMessage(null);
      try {
        setDialog({ kind: 'UNDO', preview: await history.previewUndo(targetOperationId) });
      } catch (failure) {
        setLocalMessage(message(failure));
        await history.reload('undo preview rejection').catch(() => undefined);
      } finally {
        setPreviewPending(false);
      }
    },
    [history],
  );

  const requestRedoPreview = useCallback(async () => {
    const target = capabilities?.redo?.operation.operationId;
    if (target === undefined) {
      setLocalMessage('No current redo target is reported by the server.');
      return;
    }
    setPreviewPending(true);
    setLocalMessage(null);
    try {
      setDialog({ kind: 'REDO', preview: await history.previewRedo(target) });
    } catch (failure) {
      setLocalMessage(message(failure));
      await history.reload('redo preview rejection').catch(() => undefined);
    } finally {
      setPreviewPending(false);
    }
  }, [capabilities?.redo?.operation.operationId, history]);

  const requestPersonalUndo = useCallback(async () => {
    const target = capabilities?.personalUndo?.operation.operationId;
    if (target === undefined) {
      setLocalMessage('No current personal undo target is reported by the server.');
      return;
    }
    await requestUndoPreview(target);
  }, [capabilities?.personalUndo?.operation.operationId, requestUndoPreview]);

  const requestSharedUndo = useCallback(async () => {
    if (selectedSharedTargetId === null) {
      setLocalMessage('Select an active shared operation before requesting its undo preview.');
      return;
    }
    await requestUndoPreview(selectedSharedTargetId);
  }, [requestUndoPreview, selectedSharedTargetId]);

  const confirmDialog = useCallback(async () => {
    if (dialog === null || !dialog.preview.safe) {
      return;
    }
    setPreviewPending(true);
    try {
      if (dialog.kind === 'UNDO') {
        await history.executeUndo(dialog.preview);
      } else {
        await history.executeRedo(dialog.preview);
      }
      setDialog(null);
      setLocalMessage(null);
    } catch (failure) {
      setDialog(null);
      setLocalMessage(message(failure));
    } finally {
      setPreviewPending(false);
    }
  }, [dialog, history]);

  useEffect(() => {
    const listener = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || event.key.toLowerCase() !== 'z') {
        return;
      }
      const target = event.target;
      if (
        target instanceof HTMLInputElement ||
        target instanceof HTMLTextAreaElement ||
        target instanceof HTMLSelectElement ||
        (target instanceof HTMLElement && target.isContentEditable)
      ) {
        return;
      }
      if (mode === null) {
        return;
      }
      const action = historyShortcutAction({
        mode,
        shiftKey: event.shiftKey,
        personalUndoEnabled: policy.personalUndoEnabled,
        redoEnabled: policy.redoEnabled,
        sharedTargetSelected: selectedSharedTargetId !== null,
      });
      if (action === 'none') {
        return;
      }
      event.preventDefault();
      if (action === 'personal-undo') {
        void requestPersonalUndo();
      } else if (action === 'redo') {
        void requestRedoPreview();
      } else if (action === 'shared-preview') {
        void requestSharedUndo();
      } else {
        setLocalMessage('Select an active shared operation before using the undo shortcut.');
      }
    };
    window.addEventListener('keydown', listener);
    return () => window.removeEventListener('keydown', listener);
  }, [
    mode,
    policy.personalUndoEnabled,
    policy.redoEnabled,
    requestPersonalUndo,
    requestRedoPreview,
    requestSharedUndo,
    selectedSharedTargetId,
  ]);

  if (collaboration.session === null || mode === null) {
    return null;
  }

  const personalUndo = capabilities?.personalUndo ?? null;
  const redo = capabilities?.redo ?? null;
  const problemBlockers = history.problem?.blockingOperations ?? [];

  return (
    <section className="history-controls" data-testid="history-controls">
      <h2>Semantic history</h2>
      <p className="help-text" data-testid="history-mode-explanation">
        {historyModeExplanation(mode)}
      </p>
      <dl className="history-facts">
        <div>
          <dt>Capability revision</dt>
          <dd data-testid="history-capability-revision">{capabilities?.revision ?? 'Loading'}</dd>
        </div>
        <div>
          <dt>History state</dt>
          <dd data-testid="history-controller-state">{history.status}</dd>
        </div>
      </dl>

      {policy.personalUndoVisible ? (
        <div className="history-actions">
          <button
            className="action-button"
            data-testid="personal-undo-button"
            disabled={!policy.personalUndoEnabled || previewPending}
            onClick={() => void requestPersonalUndo()}
            title={policy.personalUndoReason}
            type="button"
          >
            Undo
          </button>
          <button
            className="action-button"
            data-testid="redo-button"
            disabled={!policy.redoEnabled || previewPending}
            onClick={() => void requestRedoPreview()}
            title={policy.redoReason}
            type="button"
          >
            Redo
          </button>
        </div>
      ) : null}

      {personalUndo !== null && !personalUndo.available ? (
        <div className="history-warning" data-testid="personal-undo-unavailable">
          <strong>Undo unavailable: {personalUndo.status}</strong>
          <p className="help-text">{policy.personalUndoReason}</p>
          <Blockers blockers={personalUndo.blockingOperations} />
        </div>
      ) : null}
      {redo !== null && !redo.available ? (
        <div className="history-warning" data-testid="redo-unavailable">
          <strong>Redo unavailable: {redo.status}</strong>
          <p className="help-text">{policy.redoReason}</p>
          <Blockers blockers={redo.blockingOperations} />
        </div>
      ) : null}

      {policy.sharedUndoVisible ? (
        <div className="shared-history" data-testid="shared-undo-browser">
          <h3>Shared undo target</h3>
          {sharedTargets.length === 0 ? (
            <p className="help-text">No active shared operation is available.</p>
          ) : (
            <ul className="shared-history__list">
              {sharedTargets.map((operation) => (
                <li key={operation.operationId}>
                  <label>
                    <input
                      checked={selectedSharedTargetId === operation.operationId}
                      data-testid={`shared-target-${operation.operationId}`}
                      name="shared-undo-target"
                      onChange={() => setSelectedSharedTargetId(operation.operationId)}
                      type="radio"
                    />
                    <span>
                      <strong>{operation.operationType}</strong> by{' '}
                      {actorLabel(collaboration, operation.actorId)}
                      <span className="help-text">{formattedTime(operation.occurredAt)}</span>
                      <span className="help-text">Objects: {affectedObjects(operation)}</span>
                      {!operation.reconstructible ? (
                        <span className="history-warning">Not reconstructible</span>
                      ) : null}
                    </span>
                  </label>
                </li>
              ))}
            </ul>
          )}
          <button
            className="action-button"
            data-testid="shared-undo-preview-button"
            disabled={
              !policy.sharedUndoEnabled || selectedSharedTargetId === null || previewPending
            }
            onClick={() => void requestSharedUndo()}
            type="button"
          >
            Preview shared undo
          </button>
          {history.nextBeforeRevision === null ? null : (
            <button
              className="action-button"
              data-testid="load-older-history-button"
              disabled={policy.busy}
              onClick={() => void history.loadMore()}
              type="button"
            >
              Load older operations
            </button>
          )}
        </div>
      ) : null}

      {history.status === 'uncertain' && history.pendingCommand !== null ? (
        <div className="history-warning" data-testid="uncertain-history-command">
          <strong>Command result is uncertain</strong>
          <p>
            <code>{history.pendingCommand.commandId}</code> remains the only retry identity. The
            server history has not yet proved acceptance.
          </p>
          <button
            className="action-button"
            data-testid="retry-history-command-button"
            disabled={collaboration.connectionState !== 'live'}
            onClick={() => void history.retryPending()}
            type="button"
          >
            Retry exact command
          </button>
        </div>
      ) : null}

      {history.problem === null ? null : (
        <div className="history-warning" data-testid="history-problem">
          <strong>{history.problem.code ?? 'History request failed'}</strong>
          <p>{history.problem.detail ?? history.problem.title ?? 'Unknown history problem'}</p>
          <Blockers blockers={problemBlockers} />
          <button className="action-button" onClick={history.clearProblem} type="button">
            Dismiss
          </button>
        </div>
      )}
      {localMessage === null ? null : (
        <p className="workbench__error" data-testid="history-local-message">
          {localMessage}
        </p>
      )}

      {dialog === null ? null : (
        <PreviewDialogView
          dialog={dialog}
          pending={previewPending || history.status === 'pending'}
          onCancel={() => setDialog(null)}
          onConfirm={() => void confirmDialog()}
        />
      )}
    </section>
  );
}
