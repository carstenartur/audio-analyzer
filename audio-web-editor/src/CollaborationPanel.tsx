import { useEffect, useMemo, useRef, useState } from 'react';

import type { ActorIdentity, CollaborationMode } from './api';
import { useWorkflowHistory } from './useWorkflowHistory';
import type { WorkflowSessionController } from './useWorkflowSession';
import { WorkflowHistoryPanel } from './WorkflowHistoryPanel';

interface CollaborationPanelProps {
  controller: WorkflowSessionController;
}

const MODES: readonly CollaborationMode[] = Object.freeze([
  'PRIVATE_WORKSPACE',
  'SHARED_SESSION_PERSONAL_UNDO',
  'SHARED_SESSION_SHARED_UNDO',
]);
const ACTIVE_SESSION_STORAGE_KEY = 'audio-analyzer.workflow.active-session';

function message(failure: unknown): string {
  return failure instanceof Error ? failure.message : String(failure);
}

function storedSessionId(): string | null {
  try {
    const value = sessionStorage.getItem(ACTIVE_SESSION_STORAGE_KEY);
    return value === null || value.trim().length === 0 ? null : value;
  } catch {
    return null;
  }
}

function storeSessionId(sessionId: string | null): void {
  try {
    if (sessionId === null) {
      sessionStorage.removeItem(ACTIVE_SESSION_STORAGE_KEY);
    } else {
      sessionStorage.setItem(ACTIVE_SESSION_STORAGE_KEY, sessionId);
    }
  } catch {
    // Session operation remains valid when browser storage is unavailable.
  }
}

export function CollaborationPanel({ controller }: CollaborationPanelProps) {
  const [sessionId, setSessionId] = useState('shared-workflow');
  const [workflowName, setWorkflowName] = useState('Shared audio workflow');
  const [mode, setMode] = useState<CollaborationMode>('SHARED_SESSION_PERSONAL_UNDO');
  const [actorDraft, setActorDraft] = useState<ActorIdentity>(controller.actor);
  const [actionError, setActionError] = useState<string | null>(null);
  const [actionPending, setActionPending] = useState(false);
  const restoreAttempted = useRef(false);
  const previouslyActive = useRef(false);
  const history = useWorkflowHistory({ collaboration: controller });

  useEffect(() => {
    if (restoreAttempted.current) {
      return;
    }
    restoreAttempted.current = true;
    const rememberedSessionId = storedSessionId();
    if (rememberedSessionId === null || controller.active) {
      return;
    }
    setSessionId(rememberedSessionId);
    setActionPending(true);
    setActionError(null);
    void controller
      .joinSession(rememberedSessionId)
      .catch((failure: unknown) => {
        storeSessionId(null);
        setActionError(`Could not restore session ${rememberedSessionId}: ${message(failure)}`);
      })
      .finally(() => setActionPending(false));
  }, [controller.active, controller.joinSession]);

  useEffect(() => {
    if (controller.session !== null) {
      previouslyActive.current = true;
      storeSessionId(controller.session.sessionId);
      return;
    }
    if (previouslyActive.current) {
      previouslyActive.current = false;
      storeSessionId(null);
    }
  }, [controller.session]);

  useEffect(() => {
    if (!controller.active) {
      setActorDraft(controller.actor);
    }
  }, [controller.active, controller.actor]);

  const identityDirty = useMemo(
    () =>
      actorDraft.actorId !== controller.actor.actorId ||
      actorDraft.userId !== controller.actor.userId ||
      actorDraft.displayName !== controller.actor.displayName,
    [actorDraft, controller.actor],
  );

  const run = async (action: () => Promise<void>) => {
    setActionPending(true);
    setActionError(null);
    try {
      await action();
    } catch (failure) {
      setActionError(message(failure));
    } finally {
      setActionPending(false);
    }
  };

  const saveIdentity = () => {
    try {
      controller.updateActor(actorDraft);
      setActionError(null);
    } catch (failure) {
      setActionError(message(failure));
    }
  };

  const session = controller.session;
  const isOwner = session?.owner.actorId === controller.actor.actorId;

  return (
    <section className="collaboration" data-testid="collaboration-panel">
      <h2>Collaboration</h2>
      {session === null ? (
        <>
          <label className="field">
            Session id
            <input
              data-testid="session-id-input"
              value={sessionId}
              onChange={(event) => setSessionId(event.target.value)}
            />
          </label>
          <label className="field">
            Mode
            <select
              data-testid="session-mode-select"
              value={mode}
              onChange={(event) => setMode(event.target.value as CollaborationMode)}
            >
              {MODES.map((candidate) => (
                <option key={candidate} value={candidate}>
                  {candidate}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            Workflow name
            <input
              data-testid="workflow-name-input"
              value={workflowName}
              onChange={(event) => setWorkflowName(event.target.value)}
            />
          </label>
          <label className="field">
            Actor id
            <input
              data-testid="actor-id-input"
              value={actorDraft.actorId}
              onChange={(event) => setActorDraft({ ...actorDraft, actorId: event.target.value })}
            />
          </label>
          <label className="field">
            User id
            <input
              data-testid="user-id-input"
              value={actorDraft.userId}
              onChange={(event) => setActorDraft({ ...actorDraft, userId: event.target.value })}
            />
          </label>
          <label className="field">
            Display name
            <input
              data-testid="display-name-input"
              value={actorDraft.displayName}
              onChange={(event) => setActorDraft({ ...actorDraft, displayName: event.target.value })}
            />
          </label>
          <button
            className="action-button"
            data-testid="save-actor-button"
            disabled={!identityDirty || actionPending}
            onClick={saveIdentity}
            type="button"
          >
            Save actor identity
          </button>
          {identityDirty ? (
            <p className="help-text">Save the actor identity before creating or joining a session.</p>
          ) : null}
          <div className="collaboration__actions">
            <button
              className="action-button"
              data-testid="create-session-button"
              disabled={identityDirty || actionPending}
              onClick={() =>
                void run(() =>
                  controller.createSession({
                    sessionId,
                    mode,
                    workflowName,
                  }),
                )
              }
              type="button"
            >
              Create session
            </button>
            <button
              className="action-button"
              data-testid="join-session-button"
              disabled={identityDirty || actionPending}
              onClick={() => void run(() => controller.joinSession(sessionId))}
              type="button"
            >
              {actionPending && storedSessionId() === sessionId ? 'Restoring…' : 'Join session'}
            </button>
          </div>
        </>
      ) : (
        <>
          <dl className="collaboration__facts">
            <div>
              <dt>Session</dt>
              <dd data-testid="active-session-id">{session.sessionId}</dd>
            </div>
            <div>
              <dt>Mode</dt>
              <dd data-testid="active-session-mode">{session.mode}</dd>
            </div>
            <div>
              <dt>Connection</dt>
              <dd data-testid="connection-state">{controller.connectionState}</dd>
            </div>
            <div>
              <dt>Revision</dt>
              <dd data-testid="semantic-revision">{controller.revision}</dd>
            </div>
            <div>
              <dt>Event sequence</dt>
              <dd data-testid="event-sequence">{controller.sequence}</dd>
            </div>
            <div>
              <dt>Command</dt>
              <dd data-testid="command-state">{controller.commandState}</dd>
            </div>
          </dl>
          {controller.reconnectAttempts > 0 ? (
            <p className="help-text">Reconnect attempt {controller.reconnectAttempts}</p>
          ) : null}
          {controller.pendingOperationId === null ? null : (
            <p className="help-text" data-testid="pending-operation">
              Pending: {controller.pendingOperationId}
            </p>
          )}
          <div className="collaboration__actions">
            <button
              className="action-button"
              data-testid="leave-session-button"
              disabled={actionPending}
              onClick={() => void run(controller.leaveSession)}
              type="button"
            >
              Leave session
            </button>
            <button
              className="action-button"
              data-testid="close-session-button"
              disabled={!isOwner || actionPending}
              onClick={() => void run(controller.closeSession)}
              type="button"
            >
              Close session
            </button>
          </div>
          <WorkflowHistoryPanel collaboration={controller} history={history} />
          <h3>Participants</h3>
          <ul className="participant-list" data-testid="participant-list">
            {controller.participants.map((participant) => (
              <li key={participant.actorId} data-testid={`participant-${participant.actorId}`}>
                {participant.displayName} <span className="help-text">({participant.actorId})</span>
              </li>
            ))}
          </ul>
          <h3>Remote presence</h3>
          {controller.remotePresence.length === 0 ? (
            <p className="help-text">No current remote cursor or selection samples.</p>
          ) : (
            <ul className="presence-list" data-testid="remote-presence-list">
              {controller.remotePresence.map((sample) => (
                <li key={sample.actor.actorId} data-testid={`presence-${sample.actor.actorId}`}>
                  <strong>{sample.actor.displayName}</strong>
                  <div className="help-text">
                    {Object.entries(sample.attributes)
                      .map(([key, value]) => `${key}=${value}`)
                      .join(', ')}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
      {actionError === null ? null : (
        <p className="workbench__error" data-testid="collaboration-error">
          {actionError}
        </p>
      )}
    </section>
  );
}
