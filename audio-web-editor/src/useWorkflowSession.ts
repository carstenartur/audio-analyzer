import { useCallback, useEffect, useRef, useState } from 'react';

import {
  ApiError,
  deleteJson,
  getJson,
  postJson,
  putJson,
  setCollaborationActorId,
  type ActorIdentity,
  type CollaborationMode,
  type SessionEventResponse,
  type SessionEventType,
  type SessionResponse,
  type WorkflowProjection,
} from './api';
import { claimClientActor, releaseClientActor } from './clientIdentity.mjs';
import {
  acceptCommandProjection,
  collaborationState,
  expirePresence,
  reconnectDelay,
  recoveryForProblemCode,
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
  resetIdentity: () => void;
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
  return {
    actorId: '', displayName: '', provenance: 'ANONYMOUS', verified: false,
    accountHandle: null, role: 'EDITOR', participantCode: '', lastSeen: null,
  };
}

function validActor(value: unknown): value is ActorIdentity {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Partial<ActorIdentity>;
  return typeof candidate.actorId === 'string' && typeof candidate.displayName === 'string'
    && typeof candidate.provenance === 'string';
}

function initialActor(): ActorIdentity {
  try {
    const stored = sessionStorage.getItem(ACTOR_STORAGE_KEY);
    if (stored !== null) {
      const parsed: unknown = JSON.parse(stored);
      if (validActor(parsed)) {
        return parsed;
      }
    }
  } catch {
    // Browser storage is optional; credentials remain in server-issued HttpOnly cookies.
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
  const currentPresence = useRef<Record<string, string>>({});
  const identityPromise = useRef<Promise<ActorIdentity> | null>(null);
  const presenceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const ensureIdentity = useCallback(async () => {
    if (identityPromise.current !== null) {
      return identityPromise.current;
    }
    const task = claimClientActor(actor.actorId).then((owned) => postJson<ActorIdentity>('/workflow/identity', {
      actorId: owned ? actor.actorId || null : null, displayName: actor.displayName || null,
    })).then(async (resolved) => {
      await claimClientActor(resolved.actorId);
      setActor(resolved);
      setCollaborationActorId(resolved.actorId);
      try {
        sessionStorage.setItem(ACTOR_STORAGE_KEY, JSON.stringify(resolved));
      } catch {
        // Only the public actor selector is optional browser storage.
      }
      return resolved;
    }).catch((failure: unknown) => {
      identityPromise.current = null;
      throw failure;
    });
    identityPromise.current = task;
    return task;
  }, [actor]);

  const clearPresenceBuffer = useCallback(() => {
    presenceAttributes.current = {};
    currentPresence.current = {};
    if (presenceTimer.current !== null) {
      clearTimeout(presenceTimer.current);
      presenceTimer.current = null;
    }
  }, []);

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
      clearPresenceBuffer();
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
    [clearPresenceBuffer, commitAcceptedState, onError, onStatus],
  );

  const reset = useCallback(
    (status: string) => {
      clearPresenceBuffer();
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
    [clearPresenceBuffer, onError, onStatus],
  );

  const resetAfterFailure = useCallback((failure: unknown, activeSessionId: string) => {
    if (!(failure instanceof ApiError) || recoveryForProblemCode(failure.code) !== 'reset') {
      return false;
    }
    if (sessionId.current !== activeSessionId) {
      return true;
    }
    if (failure.code === 'COLLABORATION_IDENTITY_REQUIRED') {
      identityPromise.current = null;
      reset('Client identity expired or no longer matches your account. Start a new client identity to reconnect.');
    } else {
      reset(`Session ${activeSessionId} no longer exists`);
    }
    return true;
  }, [reset]);

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
        await postJson<SessionResponse>(`${basePath}/join`, { actorId: actor.actorId });
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
          if (resetAfterFailure(failure, activeSessionId)) {
            return;
          }
          onError(errorMessage(failure));
          throw failure;
        })
        .finally(() => {
          reconciliation.current = null;
        });
      reconciliation.current = task;
      return task;
    },
    [actor.actorId, commitAcceptedState, onError, onStatus, resetAfterFailure],
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
        void reconcile('invalid SSE payload').catch(() => undefined);
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
        void reconcile('SSE sequence gap').catch(() => undefined);
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
      if (event.type === 'PRESENCE_LEFT' && event.actor?.actorId === actor.actorId) {
        source?.close();
        reset(`You left collaboration session ${activeSessionId}`);
      }
      if (reduced.state.closed) {
        source?.close();
        reset(`Session ${activeSessionId} was closed`);
      }
    };

    const connect = () => {
      if (cancelled || sessionId.current !== activeSessionId) {
        return;
      }
      const cursor = acceptedState.current?.sequence ?? 0;
      const url = `${encodedSessionPath(activeSessionId)}/events?afterSequence=${cursor}&actorId=${encodeURIComponent(actor.actorId)}`;
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
        if (
          cancelled ||
          acceptedState.current?.closed === true ||
          retryTimer !== null
        ) {
          return;
        }
        attempt += 1;
        setReconnectAttempts(attempt);
        setConnectionState('reconnecting');
        const delay = reconnectDelay(attempt);
        onStatus(`Reconnecting: attempt ${attempt}`);
        retryTimer = setTimeout(() => {
          retryTimer = null;
          void reconcile('transport reconnect')
            .then(connect)
            .catch((failure: unknown) => {
              if (resetAfterFailure(failure, activeSessionId)) {
                return;
              }
              onError(errorMessage(failure));
              connect();
            });
        }, delay);
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
  }, [actor.actorId, commitAcceptedState, onError, onStatus, reconcile, reset, resetAfterFailure, session?.sessionId]);

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

  useEffect(() => clearPresenceBuffer, [clearPresenceBuffer]);

  const updateActor = useCallback((nextActor: ActorIdentity) => {
    if (sessionId.current !== null || actor.actorId) {
      throw new Error('This client identity keeps its original display name for history attribution');
    }
    const displayName = nextActor.displayName.trim();
    if (displayName.length > 120) {
      throw new Error('Display name must contain at most 120 characters');
    }
    setActor({ ...generatedActor(), displayName });
    identityPromise.current = null;
  }, [actor.actorId]);

  const resetIdentity = useCallback(() => {
    if (sessionId.current !== null) {
      throw new Error('Leave the current session before starting a new client identity');
    }
    releaseClientActor();
    identityPromise.current = null;
    setCollaborationActorId('');
    setActor(generatedActor());
    try {
      sessionStorage.removeItem(ACTOR_STORAGE_KEY);
    } catch {
      // An in-memory selector still permits a fresh identity.
    }
  }, []);

  const createSession = useCallback(
    async (input: CreateSessionInput) => {
      const normalizedSessionId = input.sessionId.trim();
      if (normalizedSessionId.length === 0) {
        throw new Error('Session id is required');
      }
      const resolvedActor = await ensureIdentity();
      const metadata = await postJson<SessionResponse>('/workflow/sessions', {
        sessionId: normalizedSessionId,
        mode: input.mode,
        actor: resolvedActor,
        workflowId: input.workflowId,
        workflowName: input.workflowName,
      });
      const projection = await getJson<WorkflowProjection>(
        `${encodedSessionPath(metadata.sessionId)}/projection`,
      );
      activate(metadata, projection);
    },
    [activate, ensureIdentity],
  );

  const joinSession = useCallback(
    async (requestedSessionId: string) => {
      const normalizedSessionId = requestedSessionId.trim();
      if (normalizedSessionId.length === 0) {
        throw new Error('Session id is required');
      }
      const resolvedActor = await ensureIdentity();
      const basePath = encodedSessionPath(normalizedSessionId);
      const metadata = await postJson<SessionResponse>(`${basePath}/join`, resolvedActor);
      const projection = await getJson<WorkflowProjection>(`${basePath}/projection`);
      activate(metadata, projection);
    },
    [activate, ensureIdentity],
  );

  const leaveSession = useCallback(async () => {
    const activeSessionId = sessionId.current;
    if (activeSessionId === null) {
      return;
    }
    try {
      await postJson<SessionResponse>(`${encodedSessionPath(activeSessionId)}/leave`, {
        actorId: actor.actorId,
      });
    } catch (failure) {
      if (failure instanceof ApiError && failure.code === 'ACTOR_NOT_JOINED') {
        reset(`Left collaboration session ${activeSessionId}`);
        return;
      }
      if (resetAfterFailure(failure, activeSessionId)) {
        return;
      }
      throw failure;
    }
    reset(`Left collaboration session ${activeSessionId}`);
  }, [actor.actorId, reset, resetAfterFailure]);

  const closeSession = useCallback(async () => {
    const activeSessionId = sessionId.current;
    if (activeSessionId === null) {
      return;
    }
    try {
      await deleteJson<void>(encodedSessionPath(activeSessionId), { actorId: actor.actorId });
    } catch (failure) {
      if (resetAfterFailure(failure, activeSessionId)) {
        return;
      }
      throw failure;
    }
    reset(`Closed collaboration session ${activeSessionId}`);
  }, [actor.actorId, reset, resetAfterFailure]);

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
      const basePath = encodedSessionPath(activeSession.sessionId);
      setPendingOperationId(operationId);
      setCommandState('pending');
      onError(null);
      onStatus(`Submitting: ${operationId}`);
      try {
        const projection = await postJson<WorkflowProjection>(`${basePath}/operations`, {
          mode: activeSession.mode,
          actor,
          expectedRevision,
          operation: { ...operation, author: actor.actorId },
        });
        const metadata = await getJson<SessionResponse>(basePath).catch(() => null);
        if (metadata !== null) {
          setSession((current) =>
            current === null ? null : { ...current, revision: metadata.revision },
          );
        }
        const current = acceptedState.current;
        if (current !== null && current.revision === expectedRevision) {
          commitAcceptedState(
            acceptCommandProjection(
              current,
              projection,
              metadata?.revision ?? expectedRevision + 1,
            ),
          );
        }
        if (metadata === null) {
          void reconcile('command metadata unavailable').catch(() => undefined);
        }
        setCommandState('accepted');
        onStatus(`Loaded: server accepted operation at revision ${metadata?.revision ?? expectedRevision + 1}`);
        return projection;
      } catch (failure) {
        setCommandState('rejected');
        onError(errorMessage(failure));
        const recovery =
          failure instanceof ApiError ? recoveryForProblemCode(failure.code) : 'reject';
        if (recovery === 'reconcile') {
          await reconcile(failure instanceof ApiError ? failure.code ?? 'conflict' : 'conflict');
        } else if (recovery === 'reset') {
          resetAfterFailure(failure, activeSession.sessionId);
        }
        throw failure;
      } finally {
        setPendingOperationId(null);
      }
    },
    [actor, commitAcceptedState, onError, onStatus, reconcile, resetAfterFailure, session],
  );

  const publishPresence = useCallback(
    (attributes: Record<string, string>) => {
      if (sessionId.current === null || acceptedState.current?.closed === true) {
        return;
      }
      currentPresence.current = { ...currentPresence.current, ...attributes };
      presenceAttributes.current = { ...currentPresence.current };
      if (presenceTimer.current !== null) {
        return;
      }
      presenceTimer.current = setTimeout(() => {
        presenceTimer.current = null;
        const activeSessionId = sessionId.current;
        if (activeSessionId === null || acceptedState.current?.closed === true) {
          presenceAttributes.current = {};
          return;
        }
        const nextAttributes = presenceAttributes.current;
        presenceAttributes.current = {};
        void putJson(`${encodedSessionPath(activeSessionId)}/presence`, {
          actor,
          observedAt: new Date().toISOString(),
          attributes: nextAttributes,
        }).catch((failure: unknown) => {
          if (failure instanceof ApiError && failure.code === 'ACTOR_NOT_JOINED') {
            setConnectionState('reconnecting');
            void reconcile('presence membership recovery').catch(() => undefined);
            return;
          }
          if (!resetAfterFailure(failure, activeSessionId)) {
            onError(errorMessage(failure));
          }
        });
      }, PRESENCE_THROTTLE_MILLIS);
    },
    [actor, onError, reconcile, resetAfterFailure],
  );

  useEffect(() => {
    if (session === null) {
      return undefined;
    }
    const timer = setInterval(() => publishPresence({}), 5_000);
    return () => clearInterval(timer);
  }, [publishPresence, session]);

  return {
    actor,
    updateActor,
    resetIdentity,
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
