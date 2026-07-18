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

async function responseJson<T>(response: Response, url: string): Promise<T> {
  if (response.status === 422) {
    const validation = (await response.json()) as ValidationResponse;
    throw new Error(`Operation rejected: ${validation.violations.join('; ')}`);
  }
  if (!response.ok) {
    throw new Error(`${url} failed: ${response.status}`);
  }
  return (await response.json()) as T;
}

export async function getJson<T>(url: string): Promise<T> {
  return responseJson<T>(await fetch(url, { headers: { Accept: 'application/json' } }), url);
}

export async function postJson<T>(url: string, body: unknown): Promise<T> {
  return responseJson<T>(
    await fetch(url, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(body),
    }),
    url,
  );
}
