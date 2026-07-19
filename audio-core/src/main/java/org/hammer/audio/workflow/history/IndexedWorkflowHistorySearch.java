package org.hammer.audio.workflow.history;

import java.util.List;

/** Application boundary for a rebuildable indexed view of workflow checkpoint history. */
public interface IndexedWorkflowHistorySearch {

  /** Searches the derived projection and returns exact commit identities. */
  List<WorkflowHistoryTextResult> search(WorkflowHistoryTextQuery query);

  /** Rebuilds missing projections for commits reachable from one branch head. */
  int rebuild(String branch, int limit);
}
