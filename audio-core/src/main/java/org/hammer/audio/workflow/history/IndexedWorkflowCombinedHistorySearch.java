package org.hammer.audio.workflow.history;

import java.util.List;

/** Optional capability for one correctly composed generic and semantic history query. */
@FunctionalInterface
public interface IndexedWorkflowCombinedHistorySearch {

  /**
   * Searches semantic candidates through the generic history query before applying the final limit.
   *
   * @param query combined query contract
   * @return matching exact-commit evidence in generic ranking/order
   */
  List<WorkflowCombinedHistoryResult> searchCombined(WorkflowCombinedHistoryQuery query);
}
