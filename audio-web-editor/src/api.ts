export interface HandleProjection {
  id: string;
  name: string;
  dataType: string;
}

export interface NodeProjection {
  id: string;
  type: string;
  label: string;
  inputHandles: HandleProjection[];
  outputHandles: HandleProjection[];
  properties: Record<string, string>;
}

export interface EdgeProjection {
  id: string;
  source: string;
  sourceHandle: string;
  target: string;
  targetHandle: string;
}

export interface WorkflowProjection {
  workflowId: string;
  workflowName: string;
  nodes: NodeProjection[];
  edges: EdgeProjection[];
}

export interface CatalogEntry {
  type: string;
  label: string;
  inputHandles: HandleProjection[];
  outputHandles: HandleProjection[];
}

export interface ValidationResponse {
  violations: string[];
}

export type CollaborationMode =
  | 'PRIVATE_WORKSPACE'
  | 'SHARED_SESSION_PERSONAL_UNDO'
  | 'SHARED_SESSION_SHARED_UNDO';

export interface ActorIdentity {
  actorId: string;
  userId: string;
  displayName: string;
}

export interface SessionResponse {
  sessionId: string;
  mode: CollaborationMode;
  owner: ActorIdentity;
  createdAt: string;
  participants: ActorIdentity[];
  operationCount: number;
  workflowId: string;
  revision: number;
  sequence: number;
}

export type HistoryCommandKind = 'NORMAL' | 'UNDO' | 'REDO';
export type HistoryActionStatus = 'AVAILABLE' | 'BLOCKED' | 'NOT_RECONSTRUCTIBLE';

export interface HistoryOperationResponse {
  operationId: string;
  operationType: string;
  actorId: string;
  occurredAt: string;
  revision: number;
  sequence: number;
  commandKind: HistoryCommandKind;
  commandId: string;
  targetOperationId: string | null;
  affectedObjectIds: string[];
  reconstructible: boolean;
  activeUndoTarget: boolean;
  activeRedoTarget: boolean;
}

export interface BlockingOperationResponse {
  operationId: string;
  actorId: string;
  conflictingObjectIds: string[];
}

export interface HistoryActionResponse {
  operation: HistoryOperationResponse;
  status: HistoryActionStatus;
  available: boolean;
  blockingOperations: BlockingOperationResponse[];
}

export interface HistoryCapabilitiesResponse {
  mode: CollaborationMode;
  revision: number;
  personalUndoPermitted: boolean;
  personalUndo: HistoryActionResponse | null;
  redo: HistoryActionResponse | null;
  sharedUndoPermitted: boolean;
}

export interface HistoryPageResponse {
  operations: HistoryOperationResponse[];
  nextBeforeRevision: number | null;
  currentRevision: number;
}

export interface UndoPreviewResponse {
  previewId: string;
  targetOperationId: string;
  targetActorId: string;
  targetOccurredAt: string;
  operationType: string;
  affectedObjectIds: string[];
  revision: number;
  safe: boolean;
  blockingOperations: BlockingOperationResponse[];
}

export interface RedoPreviewResponse {
  targetUndoOperationId: string;
  targetActorId: string;
  targetOccurredAt: string;
  operationType: string;
  affectedObjectIds: string[];
  revision: number;
  safe: boolean;
  blockingOperations: BlockingOperationResponse[];
}

export interface HistoryCommandResponse {
  projection: WorkflowProjection;
  commandKind: 'UNDO' | 'REDO';
  commandId: string;
  targetOperationId: string;
  operationId: string;
  revision: number;
  sequence: number;
}

export interface PresenceResponse {
  actorId: string;
  observedAt: string;
  attributes: Record<string, string>;
}

export type SessionEventType =
  | 'SESSION_CREATED'
  | 'SESSION_CLOSED'
  | 'OPERATION_ACCEPTED'
  | 'PRESENCE_JOINED'
  | 'PRESENCE_UPDATED'
  | 'PRESENCE_LEFT'
  | 'SNAPSHOT';

export interface SessionEventResponse {
  eventId: string;
  sessionId: string;
  sequence: number;
  revision: number;
  occurredAt: string;
  type: SessionEventType;
  actor: ActorIdentity | null;
  operationId: string | null;
  projection: WorkflowProjection | null;
  attributes: Record<string, string>;
}

export interface ApiProblem {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  code?: string;
  violations?: unknown[];
  blockingOperations?: BlockingOperationResponse[];
  targetOperationId?: string;
  [key: string]: unknown;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string | null;
  readonly problem: ApiProblem | null;

  constructor(url: string, status: number, problem: ApiProblem | null) {
    const detail = problem?.detail ?? problem?.title ?? `${url} failed: ${status}`;
    super(detail);
    this.name = 'ApiError';
    this.status = status;
    this.code = typeof problem?.code === 'string' ? problem.code : null;
    this.problem = problem;
  }
}

async function problemDetail(response: Response): Promise<ApiProblem | null> {
  const contentType = response.headers.get('content-type') ?? '';
  if (!contentType.includes('json')) {
    return null;
  }
  try {
    return (await response.json()) as ApiProblem;
  } catch {
    return null;
  }
}

async function responseJson<T>(response: Response, url: string): Promise<T> {
  if (!response.ok) {
    throw new ApiError(url, response.status, await problemDetail(response));
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

async function requestJson<T>(url: string, method: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  return responseJson<T>(
    await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
    url,
  );
}

export async function getJson<T>(url: string): Promise<T> {
  return requestJson<T>(url, 'GET');
}

export async function postJson<T>(url: string, body: unknown): Promise<T> {
  return requestJson<T>(url, 'POST', body);
}

export async function putJson<T>(url: string, body: unknown): Promise<T> {
  return requestJson<T>(url, 'PUT', body);
}

export async function deleteJson<T>(url: string, body: unknown): Promise<T> {
  return requestJson<T>(url, 'DELETE', body);
}
