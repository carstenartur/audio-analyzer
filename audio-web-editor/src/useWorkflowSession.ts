import { useCallback, useEffect, useRef, useState } from 'react';

import {
  ApiError,
  deleteJson,
  getJson,
  postJson,
  putJson,
  type ActorIdentity,
  type CollaborationMode,
  type SessionEventResponse,
  type SessionEventType,
  type SessionResponse,
  type WorkflowProjection,
} from './api';
import {
  acceptCommandProjection,
  collaborationState,
  expirePresence,
  reconnectDelay,
  reduceSessionEvent,
} from './collaborationState.mjs';

const ACTOR_STORAGE_KEY = 'audio-analyzer.workflow.actor';
const PRESENCE_THROTTLE_MILLIS = 150;
const PRESENCE_TTL_MILLIS = 15_000;
const SESSION_EVENT_TYPES: readonly SessionEventType[] = Object.freeze([
  'SESSION_CREATED',
  'SESSION_CLOSED',
  'OPERATION_ACCEPTED',
  'PRESENCE_JOINED',
  'PRESENCE_UPDATED',
  'PRESENCE_LEFT',
  'SNAPSHOT',
]);

type ConnectionState = 'idle' | 'connecting' | 'live' | 'reconnecting' | 'closed';
type CommandState = 'idle' | 'pending' | 'accepted' | 'rejected';
type AcceptedState = ReturnType<typeof collaborationState>;

interface WorkflowSessionCallbacks {
  onProjection: (projection: WorkflowProjection) => void;
  onError: (message: string | null) => void;
  onStatus: (message: string) => void;
}

export interface CreateSessionInput {
  sessionId: string;
  mode: CollaborationMode;
  workflowId?: string;
  workflowName?: string;
}

export interface RemotePresence {
  actor: ActorIdentity;
  observedAt: string;
  attributes: Record<string, string>;
}

export interface WorkflowSessionController {
  actor: ActorIdentity;
  updateActor: (actor: ActorIdentity) => void;
  session: SessionResponse | null;
  active: boolean;
  connectionState: ConnectionState;
  reconnectAttempts: number;
  commandState: CommandState;
  pendingOperationId: string | null;
  revision: number;
  sequence: number;
  participants: ActorIdentity[];
  remotePresence: RemotePresence[];
  createSession: (input: CreateSessionInput) => Promise<void>;
  joinSession: (sessionId: string) => Promise<void>;
  leaveSession: () => Promise<void>;
  closeSession: () => Promise<void>;
  submitOperation: (operation: Record<string, unknown>) => Promise<WorkflowProjection>;
  publishPresence: (attributes: Record<string, string>) => void;
  reconcile: (reason: string) => Promise<void>;
}

function generatedActor(): ActorIdentity {
  const suffix = crypto.randomUUID().slice(0, 8);
  return {
    actorId: `actor-${suffix}`,
    userId: `user-${suffix}`,
    displayName: `Browser ${suffix}`,
  };
}

function validActor(value: unknown): value is ActorIdentity {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const actor = value as Partial<ActorIdentity>;
  return Boolean(actor.actorId?.trim() && actor.userId?.trim() && actor.displayName?.trim());
}

function initialActor(): ActorIdentity {
  try {
    const stored = localStorage.getItem(ACTOR_STORAGE_KEY);
    if (stored !== null) {
      const parsed: unknown = JSON.parse(stored);
      if (validActor(parsed)) {
        return parsed;
      }
    }
  } catch {
    // A private browsing policy may make localStorage unavailable; generated identity is still stable
    // for the lifetime of this page.
  }
  return generatedActor();
}

function errorMessage(failure: unknown): string {
  return failure instanceof Error ? failure.message : String(failure);
}

function encodedSessionPath(sessionId: string): string {
  return `/workflow/sessions/${encodeURIComponent(sessionId)}`;
}

export function useWorkflowSession(callbacks: WorkflowSessionCallbacks): WorkflowSessionController {
  const { onError, onProjection, onStatus } = callbacks;
  const [actor, setActor] = useState<ActorIdentity>(initialActor);
  const [session, setSession] = useState<SessionResponse | null>(null);
  const [connectionState, setConnectionState] = useState<ConnectionState>('idle');
  const [reconnectAttempts, setReconnectAttempts] = useState(0);
  const [commandState, setCommandState] = useState<CommandState>('idle');
  const [pendingOperationId, setPendingOperationId] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  const [sequence, setSequence] = useState(0);
  const [participants, setParticipants] = useState<ActorIdentity[]>([]);
  const [remotePresence, setRemotePresence] = useState<RemotePresence[]>([]);

  const acceptedState = useRef<AcceptedState | null>(null);
  const sessionId = useRef<string | null>(null);
  const reconciliation = useRef<Promise<void> | null>(null);
  const presenceAttributes = useRef<Record<string, string>>({});
  const presenceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const commitAcceptedState = useCallback(
    (next: AcceptedState) => {
      const previous = acceptedState.current;
      acceptedState.current = next;
      setRevision(next.revision);
      setSequence(next.sequence);
      setParticipants(next.participants);
      setRemotePresence(
        Object.values(next.presence)
          .filter((sample) => sample.actor.actorId !== actor.actorId)
          .sort((left, right) => left.actor.actorId.localeCompare(right.actor.actorId)),
      );
      if (next.projection !== null && next.projection !== previous?.projection) {
        onProjection(next.projection as WorkflowProjection);
      }
    },
    [actor.actorId, onProjection],
  );

  const activate = useCallback(
    (metadata: SessionResponse, projection: WorkflowProjection) => {
      sessionId.current = metadata.sessionId;
      setSession(metadata);
      setConnectionState('connecting');
      setReconnectAttempts(0);
      setCommandState('idle');
      setPendingOperationId(null);
      commitAcceptedState(collaborationState(metadata, projection));
      onError(null);
      onStatus(`Loaded: collaboration session ${metadata.sessionId}`);
    },
    [commitAcceptedState, onError, onStatus],
  );

  const reset = useCallback(
    (status: string) => {
      sessionId.current = null;
      acceptedState.current = null;
      setSession(null);
      setConnectionState('idle');
      setReconnectAttempts(0);
      setCommandState('idle');
      setPendingOperationId(null);
      setRevision(0);
      setSequence(0);
      setParticipants([]);
      setRemotePresence([]);
      onError(null);
      onStatus(status);
    },
    [onError, onStatus],
  );

  const reconcile = useCallback(
    async (reason: string) => {
      const activeSessionId = sessionId.current;
      if (activeSessionId === null) {
        return;
      }
      if (reconciliation.current !== null) {
        return reconciliation.current;
      }
      const task = (async () => {
        const basePath = encodedSessionPath(activeSessionId);
        const [projection, metadata] = await Promise.all([
          getJson<WorkflowProjection>(`${basePath}/projection`),
          getJson<SessionResponse>(basePath),
        ]);
        if (sessionId.current !== activeSessionId) {
          return;
        }
        setSession(metadata);
        commitAcceptedState(collaborationState(metadata, projection));
        setCommandState('idle');
        onError(null);
        onStatus(`Loaded: reconciled ${activeSessionId} after ${reason}`);
      })()
        .catch((failure: unknown) => {
          onError(errorMessage(failure));
          throw failure;
        })
        .finally(() => {
          reconciliation.current = null;
        });
      reconciliation.current = task;
      return task;
    },
    [commitAcceptedState, onError, onStatus],
  );

  useEffect(() => {
    const activeSessionId = session?.sessionId;
    if (activeSessionId === undefined) {
      return undefined;
    }

    let cancelled = false;
    let source: EventSource | null = null;
    let retryTimer: ReturnType<typeof setTimeout> | null = null;
    let attempt = 0;

    const handleEvent = (message: MessageEvent<string>) => {
      let event: SessionEventResponse;
      try {
        event = JSON.parse(message.data) as SessionEventResponse;
      } catch (failure) {
        onError(`Invalid collaboration event: ${errorMessage(failure)}`);
        void reconcile('invalid SSE payload');
        return;
      }
      if (event.sessionId !== activeSessionId || acceptedState.current === null) {
        return;
      }
      const reduced = reduceSessionEvent(acceptedState.current, event);
      if (reduced.kind === 'duplicate') {
        return;
      }
      if (reduced.kind === 'reconcile') {
        onStatus(`Reconnecting: sequence gap before ${event.sequence}`);
        void reconcile('SSE sequence gap');
        return;
      }

      commitAcceptedState(reduced.state);
      setSession((current) =>
        current === null
          ? null
          : {
              ...current,
              participants: reduced.state.participants,
              revision: reduced.state.revision,
              sequence: reduced.state.sequence,
              operationCount:
                event.type === 'OPERATION_ACCEPTED'
                  ? current.operationCount + 1
                  : current.operationCount,
            },
      );
      if (event.type === 'OPERATION_ACCEPTED') {
        setCommandState('accepted');
        onStatus(`Loaded: accepted operation at revision ${event.revision}`);
      }
      if (event.type === 'SNAPSHOT') {
        onStatus(`Loaded: canonical snapshot at sequence ${event.sequence}`);
      }
      if (reduced.state.closed) {
        source?.close();
        setConnectionState('closed');
        onStatus(`Session ${activeSessionId} was closed`);
      }
    };

    const connect = () => {
      if (cancelled) {
        return;
      }
      const cursor = acceptedState.current?.sequence ?? 0;
      const url = `${encodedSessionPath(activeSessionId)}/events?afterSequence=${cursor}`;
      setConnectionState(attempt === 0 ? 'connecting' : 'reconnecting');
      source = new EventSource(url);
      source.onopen = () => {
        attempt = 0;
        setReconnectAttempts(0);
        setConnectionState('live');
        onStatus(`Loaded: live collaboration session ${activeSessionId}`);
      };
      for (const type of SESSION_EVENT_TYPES) {
        source.addEventListener(type, handleEvent as EventListener);
      }
      source.onerror = () => {
        source?.close();
        if (cancelled || acceptedState.current?.closed === true) {
          return;
        }
        attempt += 1;
        setReconnectAttempts(attempt);
        setConnectionState('reconnecting');
        const delay = reconnectDelay(attempt);
        onStatus(`Reconnecting: attempt ${attempt}`);
        retryTimer = setTimeout(connect, delay);
      };
    };

    connect();
    return () => {
      cancelled = true;
      source?.close();
      if (retryTimer !== null) {
        clearTimeout(retryTimer);
      }
    };
  }, [commitAcceptedState, onError, onStatus, reconcile, session?.sessionId]);

  useEffect(() => {
    const timer = setInterval(() => {
      if (acceptedState.current === null) {
        return;
      }
      const next = expirePresence(acceptedState.current, Date.now(), PRESENCE_TTL_MILLIS);
      if (next !== acceptedState.current) {
        commitAcceptedState(next);
      }
    }, 5_000);
    return () => clearInterval(timer);
  }, [commitAcceptedState]);

  useEffect(
    () => () => {
      if (presenceTimer.current !== null) {
        clearTimeout(presenceTimer.current);
      }
    },
    [],
  );

  const updateActor = useCallback((nextActor: ActorIdentity) => {
    if (!validActor(nextActor)) {
      throw new Error('Actor id, user id and display name are required');
    }
    if (sessionId.current !== null) {
      throw new Error('Leave the current session before changing actor identity');
    }
    const normalized = {
      actorId: nextActor.actorId.trim(),
      userId: nextActor.userId.trim(),
      displayName: nextActor.displayName.trim(),
    };
    setActor(normalized);
    try {
      localStorage.setItem(ACTOR_STORAGE_KEY, JSON.stringify(normalized));
    } catch {
      // Identity remains valid for the current page even when storage is unavailable.
    }
  }, []);

  const createSession = useCallback(
    async (input: CreateSessionInput) => {
      const normalizedSessionId = input.sessionId.trim();
      if (normalizedSessionId.length === 0) {
        throw new Error('Session id is required');
      }
      const metadata = await postJson<SessionResponse>('/workflow/sessions', {
        sessionId: normalizedSessionId,
        mode: input.mode,
        actor,
        workflowId: input.workflowId,
        workflowName: input.workflowName,
      });
      const projection = await getJson<WorkflowProjection>(
        `${encodedSessionPath(metadata.sessionId)}/projection`,
      );
      activate(metadata, projection);
    },
    [activate, actor],
  );

  const joinSession = useCallback(
    async (requestedSessionId: string) => {
      const normalizedSessionId = requestedSessionId.trim();
      if (normalizedSessionId.length === 0) {
        throw new Error('Session id is required');
      }
      const basePath = encodedSessionPath(normalizedSessionId);
      const metadata = await postJson<SessionResponse>(`${basePath}/join`, actor);
      const projection = await getJson<WorkflowProjection>(`${basePath}/projection`);
      activate(metadata, projection);
    },
    [activate, actor],
  );

  const leaveSession = useCallback(async () => {
    const activeSessionId = sessionId.current;
    if (activeSessionId === null) {
      return;
    }
    await postJson<SessionResponse>(`${encodedSessionPath(activeSessionId)}/leave`, {
      actorId: actor.actorId,
    });
    reset(`Left collaboration session ${activeSessionId}`);
  }, [actor.actorId, reset]);

  const closeSession = useCallback(async () => {
    const activeSessionId = sessionId.current;
    if (activeSessionId === null) {
      return;
    }
    await deleteJson<void>(encodedSessionPath(activeSessionId), { actorId: actor.actorId });
    reset(`Closed collaboration session ${activeSessionId}`);
  }, [actor.actorId, reset]);

  const submitOperation = useCallback(
    async (operation: Record<string, unknown>) => {
      const activeSession = session;
      const stateAtSubmission = acceptedState.current;
      if (activeSession === null || stateAtSubmission === null) {
        throw new Error('Create or join a collaboration session before editing');
      }
      const operationId = operation.operationId;
      if (typeof operationId !== 'string' || operationId.length === 0) {
        throw new Error('Semantic operation requires a stable operationId');
      }
      const expectedRevision = stateAtSubmission.revision;
      setPendingOperationId(operationId);
      setCommandState('pending');
      onError(null);
      onStatus(`Submitting: ${operationId}`);
      try {
        const projection = await postJson<WorkflowProjection>(
          `${encodedSessionPath(activeSession.sessionId)}/operations`,
          {
            mode: activeSession.mode,
            actor,
            expectedRevision,
            operation: { ...operation, author: actor.actorId },
          },
        );
        const current = acceptedState.current;
        if (current !== null && current.revision === expectedRevision) {
          commitAcceptedState(acceptCommandProjection(current, projection));
        }
        setCommandState('accepted');
        onStatus(`Loaded: server accepted ${operationId}`);
        return projection;
      } catch (failure) {
        setCommandState('rejected');
        onError(errorMessage(failure));
        if (
          failure instanceof ApiError &&
          (failure.code === 'WORKFLOW_SESSION_REVISION_CONFLICT' ||
            failure.code === 'WORKFLOW_SESSION_SEQUENCE_CONFLICT')
        ) {
          await reconcile(failure.code);
        }
        throw failure;
      } finally {
        setPendingOperationId(null);
      }
    },
    [actor, commitAcceptedState, onError, onStatus, reconcile, session],
  );

  const publishPresence = useCallback(
    (attributes: Record<string, string>) => {
      presenceAttributes.current = { ...presenceAttributes.current, ...attributes };
      if (presenceTimer.current !== null) {
        return;
      }
      presenceTimer.current = setTimeout(() => {
        presenceTimer.current = null;
        const activeSessionId = sessionId.current;
        if (activeSessionId === null) {
          return;
        }
        const nextAttributes = presenceAttributes.current;
        presenceAttributes.current = {};
        void putJson(`${encodedSessionPath(activeSessionId)}/presence`, {
          actor,
          observedAt: new Date().toISOString(),
          attributes: nextAttributes,
        }).catch((failure: unknown) => onError(errorMessage(failure)));
      }, PRESENCE_THROTTLE_MILLIS);
    },
    [actor, onError],
  );

  return {
    actor,
    updateActor,
    session,
    active: session !== null && connectionState !== 'closed',
    connectionState,
    reconnectAttempts,
    commandState,
    pendingOperationId,
    revision,
    sequence,
    participants,
    remotePresence,
    createSession,
    joinSession,
    leaveSession,
    closeSession,
    submitOperation,
    publishPresence,
    reconcile,
  };
}
