import { useCallback, useEffect, useReducer, useRef, type Reducer } from 'react';

import {
  ApiError,
  postJson,
  type ActorIdentity,
  type ApiProblem,
  type HistoryCapabilitiesResponse,
  type HistoryCommandResponse,
  type HistoryOperationResponse,
  type HistoryPageResponse,
  type RedoPreviewResponse,
  type SessionResponse,
  type UndoPreviewResponse,
} from './api';
import {
  createHistoryCommandEnvelope,
  emptyHistoryState,
  historyCommandRequest,
  historyRecoveryForProblemCode,
  reduceHistoryState,
} from './historyState.mjs';
import type { WorkflowSessionController } from './useWorkflowSession';

const HISTORY_PAGE_SIZE = 100;

type HistoryStatus = 'idle' | 'loading' | 'ready' | 'pending' | 'uncertain' | 'error';
type CommandKind = 'UNDO' | 'REDO';
type HistoryEvent = Record<string, unknown> & { type: string };

export interface PendingHistoryCommand {
  readonly kind: CommandKind;
  readonly commandId: string;
  readonly expectedRevision: number;
  readonly targetOperationId: string;
  readonly previewId: string | null;
}

interface HistoryState {
  capabilities: HistoryCapabilitiesResponse | null;
  operations: HistoryOperationResponse[];
  nextBeforeRevision: number | null;
  status: HistoryStatus;
  pendingCommand: PendingHistoryCommand | null;
  problem: ApiProblem | null;
}

interface WorkflowHistoryInput {
  collaboration: WorkflowSessionController;
}

export interface WorkflowHistoryController extends HistoryState {
  reload: (reason?: string) => Promise<void>;
  loadMore: () => Promise<void>;
  previewUndo: (targetOperationId: string) => Promise<UndoPreviewResponse>;
  previewRedo: (targetUndoOperationId: string) => Promise<RedoPreviewResponse>;
  executeUndo: (preview: UndoPreviewResponse) => Promise<HistoryCommandResponse>;
  executeRedo: (preview: RedoPreviewResponse) => Promise<HistoryCommandResponse>;
  retryPending: () => Promise<HistoryCommandResponse | null>;
  clearProblem: () => void;
}

function encodedSessionPath(sessionId: string): string {
  return `/workflow/sessions/${encodeURIComponent(sessionId)}`;
}

function failureMessage(failure: unknown): string {
  return failure instanceof Error ? failure.message : String(failure);
}

function problemFor(failure: unknown): ApiProblem {
  if (failure instanceof ApiError && failure.problem !== null) {
    return failure.problem;
  }
  return { detail: failureMessage(failure) };
}

function newCommandId(kind: CommandKind): string {
  return `history.${kind.toLowerCase()}.${crypto.randomUUID()}`;
}

async function loadHistorySnapshot(
  session: SessionResponse,
  actor: ActorIdentity,
): Promise<{ capabilities: HistoryCapabilitiesResponse; page: HistoryPageResponse }> {
  const basePath = encodedSessionPath(session.sessionId);
  const [capabilities, page] = await Promise.all([
    postJson<HistoryCapabilitiesResponse>(`${basePath}/history/capabilities`, { actor }),
    postJson<HistoryPageResponse>(`${basePath}/history/query`, {
      actor,
      limit: HISTORY_PAGE_SIZE,
    }),
  ]);
  return { capabilities, page };
}

export function useWorkflowHistory({
  collaboration,
}: WorkflowHistoryInput): WorkflowHistoryController {
  const historyReducer = reduceHistoryState as unknown as Reducer<HistoryState, HistoryEvent>;
  const [state, dispatch] = useReducer(historyReducer, emptyHistoryState() as unknown as HistoryState);
  const stateRef = useRef(state);
  const loadGeneration = useRef(0);
  stateRef.current = state;

  const reload = useCallback(
    async (reason = 'session state changed') => {
      const session = collaboration.session;
      if (session === null) {
        loadGeneration.current += 1;
        dispatch({ type: 'RESET' });
        return;
      }
      const generation = ++loadGeneration.current;
      dispatch({ type: 'LOAD_STARTED' });
      try {
        const snapshot = await loadHistorySnapshot(session, collaboration.actor);
        if (generation !== loadGeneration.current) {
          return;
        }
        dispatch({ type: 'LOADED', ...snapshot });
      } catch (failure) {
        if (generation !== loadGeneration.current) {
          return;
        }
        dispatch({
          type: 'LOAD_FAILED',
          problem: {
            ...problemFor(failure),
            detail: `History reload failed after ${reason}: ${failureMessage(failure)}`,
          },
        });
        throw failure;
      }
    },
    [collaboration.actor, collaboration.session],
  );

  const loadMore = useCallback(async () => {
    const session = collaboration.session;
    const beforeRevision = stateRef.current.nextBeforeRevision;
    if (session === null || beforeRevision === null) {
      return;
    }
    const basePath = encodedSessionPath(session.sessionId);
    try {
      const page = await postJson<HistoryPageResponse>(`${basePath}/history/query`, {
        actor: collaboration.actor,
        beforeRevision,
        limit: HISTORY_PAGE_SIZE,
      });
      dispatch({ type: 'PAGE_APPENDED', page });
    } catch (failure) {
      dispatch({ type: 'LOAD_FAILED', problem: problemFor(failure) });
      throw failure;
    }
  }, [collaboration.actor, collaboration.session]);

  const previewUndo = useCallback(
    async (targetOperationId: string) => {
      const session = collaboration.session;
      if (session === null) {
        throw new Error('Join a collaboration session before previewing undo');
      }
      return postJson<UndoPreviewResponse>(
        `${encodedSessionPath(session.sessionId)}/undo/preview`,
        { actor: collaboration.actor, targetOperationId },
      );
    },
    [collaboration.actor, collaboration.session],
  );

  const previewRedo = useCallback(
    async (targetUndoOperationId: string) => {
      const session = collaboration.session;
      if (session === null) {
        throw new Error('Join a collaboration session before previewing redo');
      }
      return postJson<RedoPreviewResponse>(
        `${encodedSessionPath(session.sessionId)}/redo/preview`,
        { actor: collaboration.actor, targetUndoOperationId },
      );
    },
    [collaboration.actor, collaboration.session],
  );

  const recoverDefinitiveFailure = useCallback(
    async (failure: ApiError) => {
      const recovery = historyRecoveryForProblemCode(failure.code);
      if (recovery === 'reconcile') {
        await collaboration.reconcile(failure.code ?? 'history conflict').catch(() => undefined);
        await reload(failure.code ?? 'history conflict').catch(() => undefined);
      } else if (recovery === 'reload') {
        await reload(failure.code ?? 'history rejection').catch(() => undefined);
      } else if (recovery === 'reset') {
        await collaboration.reconcile('session no longer exists').catch(() => undefined);
      }
    },
    [collaboration, reload],
  );

  const submitEnvelope = useCallback(
    async (envelope: PendingHistoryCommand) => {
      const session = collaboration.session;
      if (session === null) {
        throw new Error('Join a collaboration session before changing history');
      }
      const request = historyCommandRequest(envelope, collaboration.actor);
      dispatch({ type: 'COMMAND_STARTED', command: envelope });

      let response: HistoryCommandResponse;
      try {
        response = await postJson<HistoryCommandResponse>(
          `${encodedSessionPath(session.sessionId)}/${request.endpoint}`,
          request.body,
        );
      } catch (failure) {
        if (!(failure instanceof ApiError)) {
          dispatch({
            type: 'COMMAND_UNCERTAIN',
            problem: {
              detail: `The server response was lost. Command ${envelope.commandId} remains pending until durable history proves its result.`,
            },
          });
          await collaboration.reconcile('uncertain history command').catch(() => undefined);
          await reload('uncertain history command').catch(() => undefined);
          throw failure;
        }

        dispatch({ type: 'COMMAND_REJECTED', problem: problemFor(failure) });
        await recoverDefinitiveFailure(failure);
        throw failure;
      }

      dispatch({ type: 'COMMAND_ACCEPTED' });
      try {
        await collaboration.reconcile(`accepted ${response.commandKind.toLowerCase()}`);
        await reload(`accepted ${response.commandKind.toLowerCase()}`);
      } catch (failure) {
        dispatch({
          type: 'LOAD_FAILED',
          problem: {
            detail: `The ${response.commandKind.toLowerCase()} was accepted, but canonical reconciliation failed: ${failureMessage(failure)}`,
          },
        });
      }
      return response;
    },
    [collaboration, recoverDefinitiveFailure, reload],
  );

  const executeUndo = useCallback(
    async (preview: UndoPreviewResponse) =>
      submitEnvelope(
        createHistoryCommandEnvelope({
          kind: 'UNDO',
          commandId: newCommandId('UNDO'),
          expectedRevision: preview.revision,
          targetOperationId: preview.targetOperationId,
          previewId:
            collaboration.session?.mode === 'SHARED_SESSION_SHARED_UNDO'
              ? preview.previewId
              : null,
        }),
      ),
    [collaboration.session?.mode, submitEnvelope],
  );

  const executeRedo = useCallback(
    async (preview: RedoPreviewResponse) =>
      submitEnvelope(
        createHistoryCommandEnvelope({
          kind: 'REDO',
          commandId: newCommandId('REDO'),
          expectedRevision: preview.revision,
          targetOperationId: preview.targetUndoOperationId,
        }),
      ),
    [submitEnvelope],
  );

  const retryPending = useCallback(async () => {
    const pending = stateRef.current.pendingCommand;
    if (pending === null) {
      return null;
    }
    return submitEnvelope(pending);
  }, [submitEnvelope]);

  const clearProblem = useCallback(() => dispatch({ type: 'PROBLEM_CLEARED' }), []);

  useEffect(() => {
    if (collaboration.session === null) {
      loadGeneration.current += 1;
      dispatch({ type: 'RESET' });
      return;
    }
    if (collaboration.connectionState !== 'live') {
      return;
    }
    void reload('join, reconnect or revision change').catch(() => undefined);
  }, [
    collaboration.actor.actorId,
    collaboration.actor.displayName,
    collaboration.actor.userId,
    collaboration.connectionState,
    collaboration.revision,
    collaboration.session?.sessionId,
    reload,
  ]);

  return {
    ...state,
    reload,
    loadMore,
    previewUndo,
    previewRedo,
    executeUndo,
    executeRedo,
    retryPending,
    clearProblem,
  };
}
