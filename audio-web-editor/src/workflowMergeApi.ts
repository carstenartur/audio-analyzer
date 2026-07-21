import { getJson, postJson, type WorkflowProjection } from './api';

export type MergeResolutionChoice = 'BASE' | 'LOCAL' | 'REMOTE' | 'DELETE' | 'CUSTOM';

export interface WorkflowHistoryEntry {
  commitId: string;
  workflowId: string;
  author: string;
  message: string;
  timestamp: string;
}

export interface WorkflowMergeConflict {
  conflictId: string;
  kind: string;
  elementKind: 'WORKFLOW' | 'NODE' | 'EDGE';
  elementId: string;
  fieldPath: string;
  baseValue: string | null;
  localValue: string | null;
  remoteValue: string | null;
  allowedChoices: MergeResolutionChoice[];
}

export interface WorkflowMergePreview {
  targetBranch: string;
  remoteBranch: string;
  baseCommitId: string;
  localCommitId: string;
  remoteCommitId: string;
  base: WorkflowProjection;
  local: WorkflowProjection;
  remote: WorkflowProjection;
  autoMerged: WorkflowProjection;
  conflicts: WorkflowMergeConflict[];
  validationViolations: string[];
  readyToCommit: boolean;
}

export interface WorkflowMergeResolutionInput {
  conflictId: string;
  choice: MergeResolutionChoice;
  customValue: string | null;
}

export interface WorkflowMergeResolveRequest {
  targetBranch: string;
  remoteBranch: string;
  baseCommitId: string;
  localCommitId: string;
  remoteCommitId: string;
  expectedHeadCommitId: string;
  resolutions: WorkflowMergeResolutionInput[];
  author: string;
  message: string;
  timestamp: string;
}

export interface WorkflowMergeResolveResponse {
  targetBranch: string;
  baseCommitId: string;
  localCommitId: string;
  remoteCommitId: string;
  mergedCommitId: string;
  workflow: WorkflowProjection;
  auditMessage: string;
}

export async function loadWorkflowBranchHistory(
  branch: string,
): Promise<WorkflowHistoryEntry[]> {
  return getJson<WorkflowHistoryEntry[]>(
    `/workflow/history?branch=${encodeURIComponent(branch)}&limit=100`,
  );
}

export async function previewWorkflowMerge(input: {
  targetBranch: string;
  remoteBranch: string;
  baseCommitId: string;
  localCommitId: string;
  remoteCommitId: string;
}): Promise<WorkflowMergePreview> {
  return postJson<WorkflowMergePreview>('/workflow/history/merge/preview', input);
}

export async function resolveWorkflowMerge(
  input: WorkflowMergeResolveRequest,
): Promise<WorkflowMergeResolveResponse> {
  return postJson<WorkflowMergeResolveResponse>('/workflow/history/merge/resolve', input);
}
