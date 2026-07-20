package org.hammer.audio.workflow.history;

import java.util.List;

/** Application boundary for branch-aware semantic workflow-history projections. */
public interface IndexedWorkflowSemanticHistorySearch {

  /** Searches the disposable semantic projection and returns exact commit identities. */
  List<WorkflowSemanticHistoryResult> searchSemantic(WorkflowSemanticHistoryQuery query);
}
