import { getJson, postJson, type ApiProblem } from './api';

export type WorkflowRunSourceKind = 'LIVE_SESSION' | 'STORED_COMMIT';
export type WorkflowRunLifecycleState =
  | 'QUEUED'
  | 'RUNNING'
  | 'CANCEL_REQUESTED'
  | 'CANCELLED'
  | 'COMPLETED'
  | 'FAILED';
export type WorkflowRunMode = 'SIMULATION' | 'COMPUTATION';

export interface WorkflowRunViolation {
  code: string;
  message: string;
  nodeId: string | null;
}

export interface WorkflowRunSource {
  kind: WorkflowRunSourceKind;
  sessionId: string | null;
  semanticRevision: number | null;
  commitId: string | null;
}

export interface WorkflowRunSnapshot {
  runId: string;
  startCommandId: string;
  state: WorkflowRunLifecycleState;
  mode: WorkflowRunMode;
  source: WorkflowRunSource;
  workflowId: string;
  snapshotId: string;
  planId: string;
  fingerprint: string;
  capturedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  progressPercent: number;
  statusMessage: string;
  violations: WorkflowRunViolation[];
}

export interface WorkflowRunResult {
  run: WorkflowRunSnapshot;
  overallStatus: string;
  nodeStatuses: Record<string, string>;
  executionStartedAt: string;
  executionCompletedAt: string;
  commitId: string | null;
  artifacts: Record<string, string>;
}

export interface WorkflowRunCommand {
  sourceKind: WorkflowRunSourceKind;
  startCommandId: string;
  sessionId: string | null;
  expectedRevision: number | null;
  commitId: string | null;
}

export interface WorkflowRunState {
  phase: 'idle' | 'starting' | 'uncertain' | 'active' | 'terminal' | 'error';
  command: WorkflowRunCommand | null;
  run: WorkflowRunSnapshot | null;
  result: WorkflowRunResult | null;
  problem: ApiProblem | null;
}

export interface HistoricalWorkflowRunSource {
  commitId: string;
  workflowId: string;
  message: string;
  timestamp: string;
}

export async function startWorkflowRun(body: Record<string, unknown>): Promise<WorkflowRunSnapshot> {
  return postJson<WorkflowRunSnapshot>('/workflow/runs', body);
}

export async function inspectWorkflowRun(runId: string): Promise<WorkflowRunSnapshot> {
  return getJson<WorkflowRunSnapshot>(`/workflow/runs/${encodeURIComponent(runId)}`);
}

export async function cancelWorkflowRun(runId: string): Promise<WorkflowRunSnapshot> {
  return postJson<WorkflowRunSnapshot>(`/workflow/runs/${encodeURIComponent(runId)}/cancel`, {});
}

export async function loadWorkflowRunResult(runId: string): Promise<WorkflowRunResult> {
  return getJson<WorkflowRunResult>(`/workflow/runs/${encodeURIComponent(runId)}/result`);
}
