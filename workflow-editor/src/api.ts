import type {
  CatalogEntry,
  CheckpointResponse,
  HistoryEntry,
  ValidationResponse,
  WorkflowOperation,
  WorkflowProjection,
  WorkflowSnapshot,
} from './model';

async function parseError(response: Response, fallback: string): Promise<Error> {
  try {
    const payload = (await response.json()) as Partial<ValidationResponse>;
    if (Array.isArray(payload.violations) && payload.violations.length > 0) {
      return new Error(payload.violations.join('; '));
    }
  } catch {
    // The response may intentionally have no JSON body.
  }
  return new Error(`${fallback}: HTTP ${response.status}`);
}

async function getJson<T>(url: string): Promise<T> {
  const response = await fetch(url, { headers: { Accept: 'application/json' } });
  if (!response.ok) {
    throw await parseError(response, `GET ${url} failed`);
  }
  return (await response.json()) as T;
}

async function postJson<T>(url: string, body: unknown): Promise<T> {
  const response = await fetch(url, {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    throw await parseError(response, `POST ${url} failed`);
  }
  return (await response.json()) as T;
}

export const workflowApi = {
  projection: (): Promise<WorkflowProjection> => getJson('/workflow/projection'),
  catalog: (): Promise<CatalogEntry[]> => getJson('/workflow/catalog'),
  validation: (): Promise<ValidationResponse> => getJson('/workflow/validation'),
  snapshot: (): Promise<WorkflowSnapshot> => getJson('/workflow/snapshot'),
  history: (branch: string): Promise<HistoryEntry[]> =>
    getJson(`/workflow/history?branch=${encodeURIComponent(branch)}&limit=20`),
  operation: (operation: WorkflowOperation): Promise<WorkflowProjection> =>
    postJson('/workflow/operations', operation),
  checkpoint: (branch: string, message: string): Promise<CheckpointResponse> =>
    postJson('/workflow/checkpoints', { branch, author: 'web-editor', message }),
  loadBranch: (branch: string): Promise<WorkflowProjection> =>
    postJson('/workflow/load', { branch }),
  loadCommit: (commitId: string): Promise<WorkflowProjection> =>
    postJson('/workflow/load', { commitId }),
};
