import type { WorkflowProjection } from './api';

export const EXPERIMENT_DOCUMENT_EXTENSION = 'audioexp';
export const EXPERIMENT_DOCUMENT_MEDIA_TYPE =
  'application/vnd.carstenartur.audio-analyzer.experiment+json';
export const DISCARD_DIRTY_HEADER = 'X-Audio-Analyzer-Discard-Dirty';

export interface ExperimentDocumentDiagnostic {
  severity: 'INFO' | 'WARNING' | 'ERROR';
  pointer: string;
  code: string;
  message: string;
}

export interface ExperimentPluginRequirement {
  id: string;
  versionRange: string;
  sections: string[];
}

export interface ExperimentDocumentPreviewResponse {
  format: string;
  formatVersion: number;
  experimentId: string;
  experimentName: string;
  sourceMode: string;
  canonicalSha256: string;
  workflowId: string;
  workflowName: string;
  nodeCount: number;
  edgeCount: number;
  requiredPlugins: ExperimentPluginRequirement[];
  diagnostics: ExperimentDocumentDiagnostic[];
  migrations: string[];
  executionAllowed: boolean;
  readOnly: boolean;
  canonicalDocument: string;
  sectionHashes: Record<string, string>;
  installedSections: Array<{ pluginId: string; packageVersion: string; sectionId: string; schemaVersion: number; algorithmVersion: string; parameters: Array<{ pointer: string; type: string; required: boolean; description: string }> }>;
  localRequirements: ExperimentDocumentDiagnostic[];
  comparison: { currentWorkflowSha256: string; dirty: boolean; changes: Array<{ kind: string; targetId: string; field: string; before: string | null; after: string | null }> } | null;
}

interface ExperimentDocumentErrorResponse {
  code?: unknown;
  pointer?: unknown;
  message?: unknown;
}

export class ExperimentDocumentApiError extends Error {
  readonly status: number;
  readonly code: string | null;
  readonly pointer: string | null;

  constructor(status: number, code: string | null, pointer: string | null, message: string) {
    super(message);
    this.name = 'ExperimentDocumentApiError';
    this.status = status;
    this.code = code;
    this.pointer = pointer;
  }
}

async function throwDocumentError(response: Response): Promise<never> {
  let problem: ExperimentDocumentErrorResponse | null = null;
  const contentType = response.headers.get('content-type') ?? '';
  if (contentType.includes('json')) {
    try {
      problem = (await response.json()) as ExperimentDocumentErrorResponse;
    } catch {
      problem = null;
    }
  }
  const code = typeof problem?.code === 'string' ? problem.code : null;
  const pointer = typeof problem?.pointer === 'string' ? problem.pointer : null;
  const message =
    typeof problem?.message === 'string'
      ? problem.message
      : `Experiment document request failed: ${response.status}`;
  throw new ExperimentDocumentApiError(response.status, code, pointer, message);
}

async function postDocument(
  file: File,
  endpoint: string,
  accept: string,
  additionalHeaders: Record<string, string> = {},
  signal?: AbortSignal,
): Promise<Response> {
  const response = await fetch(endpoint, {
    method: 'POST',
    headers: {
      Accept: accept,
      'Content-Type': EXPERIMENT_DOCUMENT_MEDIA_TYPE,
      ...additionalHeaders,
    },
    body: file,
    signal,
  });
  if (!response.ok) {
    await throwDocumentError(response);
  }
  return response;
}

/** Upload and preview one document without applying or executing it. */
export async function previewExperimentDocument(
  file: File,
  signal?: AbortSignal,
): Promise<ExperimentDocumentPreviewResponse> {
  const response = await postDocument(file, '/experiment-documents/preview', 'application/json', {}, signal);
  return (await response.json()) as ExperimentDocumentPreviewResponse;
}

export interface NormalizedExperimentDocument {
  blob: Blob;
  filename: string;
}

/** Upload and return a canonical normalized copy. */
export async function normalizeExperimentDocument(
  file: File,
  signal?: AbortSignal,
): Promise<NormalizedExperimentDocument> {
  const response = await postDocument(
    file,
    '/experiment-documents/normalize',
    EXPERIMENT_DOCUMENT_MEDIA_TYPE,
    {}, signal,
  );
  const contentType = response.headers.get('content-type') ?? '';
  if (!contentType.includes(EXPERIMENT_DOCUMENT_MEDIA_TYPE)) {
    throw new ExperimentDocumentApiError(
      response.status,
      'unexpected-media-type',
      '/',
      `Normalization returned unexpected media type: ${contentType || 'missing'}`,
    );
  }
  return {
    blob: await response.blob(),
    filename: attachmentFilename(response.headers.get('content-disposition')),
  };
}

export interface ExperimentDocumentApplyResponse {
  canonicalSha256: string;
  projection: WorkflowProjection;
  dirty: boolean;
}

/** Reupload and apply a previously previewed document under explicit preconditions. */
export async function applyExperimentDocument(
  file: File,
  canonicalSha256: string,
  discardDirty: boolean,
  currentWorkflowSha256?: string,
  signal?: AbortSignal,
): Promise<ExperimentDocumentApplyResponse> {
  const response = await postDocument(file, '/experiment-documents/apply', 'application/json', {
    'If-Match': `"${canonicalSha256}"`,
    [DISCARD_DIRTY_HEADER]: String(discardDirty),
    ...(currentWorkflowSha256 ? { 'X-Audio-Analyzer-Current-Workflow': currentWorkflowSha256 } : {}),
  }, signal);
  return (await response.json()) as ExperimentDocumentApplyResponse;
}

function attachmentFilename(contentDisposition: string | null): string {
  const match = /filename="([^"\\/]+)"/i.exec(contentDisposition ?? '');
  return match?.[1] ?? `normalized.${EXPERIMENT_DOCUMENT_EXTENSION}`;
}

export interface ExperimentDocumentSessionStatus {
  open: boolean;
  dirty: boolean;
  lastSavedAt: string | null;
  canonicalSha256: string | null;
  readOnly: boolean;
}

export interface ExperimentDocumentCopy {
  id: string;
  name: string;
  canonicalSha256: string;
  readOnly: boolean;
}

export interface ExperimentBindingRequest {
  document: string;
  assetPaths: Record<string, string>;
  outputDirectory: string;
  deviceId: string;
  deviceChannels: number;
  channelMapping: number[];
  synchronizationConfirmed: boolean;
  calibrationConfirmed: boolean;
  confirmedMediaTypes: Record<string, string>;
}

export async function documentSessionStatus(): Promise<ExperimentDocumentSessionStatus> {
  const response = await fetch('/experiment-documents/session');
  if (!response.ok) await throwDocumentError(response);
  return (await response.json()) as ExperimentDocumentSessionStatus;
}

export async function downloadCurrentDocument(): Promise<NormalizedExperimentDocument> {
  const response = await fetch('/experiment-documents/current');
  if (!response.ok) await throwDocumentError(response);
  return { blob: await response.blob(), filename: 'experiment.audioexp' };
}

export async function acknowledgeDocumentSaved(hash: string): Promise<void> {
  const response = await fetch('/experiment-documents/saved', {
    method: 'POST', headers: { 'If-Match': `"${hash}"` },
  });
  if (!response.ok) await throwDocumentError(response);
}

export async function importExperimentCopy(file: File, hash: string, signal?: AbortSignal): Promise<ExperimentDocumentCopy> {
  const response = await postDocument(file, '/experiment-documents/import', 'application/json', { 'If-Match': `"${hash}"` }, signal);
  return (await response.json()) as ExperimentDocumentCopy;
}

export async function listExperimentCopies(): Promise<ExperimentDocumentCopy[]> {
  const response = await fetch('/experiment-documents/copies');
  if (!response.ok) await throwDocumentError(response);
  return (await response.json()) as ExperimentDocumentCopy[];
}

export async function getExperimentCopy(id: string): Promise<File> {
  const response = await fetch(`/experiment-documents/copies/${encodeURIComponent(id)}`);
  if (!response.ok) await throwDocumentError(response);
  return new File([await response.blob()], 'experiment-copy.audioexp', { type: EXPERIMENT_DOCUMENT_MEDIA_TYPE });
}

export async function inspectExperimentBindings(request: ExperimentBindingRequest, signal?: AbortSignal): Promise<{ ready: boolean; diagnostics: ExperimentDocumentDiagnostic[] }> {
  const response = await fetch('/experiment-documents/bindings', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(request), signal,
  });
  if (!response.ok) await throwDocumentError(response);
  return (await response.json()) as { ready: boolean; diagnostics: ExperimentDocumentDiagnostic[] };
}

export async function exportExperimentEvidence(request: ExperimentBindingRequest, signal?: AbortSignal): Promise<Blob> {
  const response = await fetch('/experiment-documents/evidence', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(request), signal,
  });
  if (!response.ok) await throwDocumentError(response);
  return await response.blob();
}
