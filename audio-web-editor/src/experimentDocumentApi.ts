export const EXPERIMENT_DOCUMENT_EXTENSION = 'audioexp';
export const EXPERIMENT_DOCUMENT_MEDIA_TYPE =
  'application/vnd.carstenartur.audio-analyzer.experiment+json';

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

async function postDocument(file: File, endpoint: string, accept: string): Promise<Response> {
  const response = await fetch(endpoint, {
    method: 'POST',
    headers: {
      Accept: accept,
      'Content-Type': EXPERIMENT_DOCUMENT_MEDIA_TYPE,
    },
    body: file,
  });
  if (!response.ok) {
    await throwDocumentError(response);
  }
  return response;
}

/** Upload and preview one document without applying or executing it. */
export async function previewExperimentDocument(
  file: File,
): Promise<ExperimentDocumentPreviewResponse> {
  const response = await postDocument(file, '/experiment-documents/preview', 'application/json');
  return (await response.json()) as ExperimentDocumentPreviewResponse;
}

export interface NormalizedExperimentDocument {
  blob: Blob;
  filename: string;
}

/** Upload and return a canonical normalized copy. */
export async function normalizeExperimentDocument(
  file: File,
): Promise<NormalizedExperimentDocument> {
  const response = await postDocument(
    file,
    '/experiment-documents/normalize',
    EXPERIMENT_DOCUMENT_MEDIA_TYPE,
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

function attachmentFilename(contentDisposition: string | null): string {
  const match = /filename="([^"\\/]+)"/i.exec(contentDisposition ?? '');
  return match?.[1] ?? `normalized.${EXPERIMENT_DOCUMENT_EXTENSION}`;
}
