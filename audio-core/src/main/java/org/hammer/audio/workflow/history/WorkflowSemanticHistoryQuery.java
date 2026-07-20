package org.hammer.audio.workflow.history;

/**
 * Bounded semantic-history query for one workflow branch.
 *
 * @param branch required branch whose reachable semantic projection is searched
 * @param workflowId optional exact workflow identifier
 * @param nodeId optional exact node identifier
 * @param nodeType optional exact node type
 * @param labelText optional full-text expression over workflow names and node labels
 * @param propertyKey optional exact workflow/node metadata key
 * @param propertyValue optional exact workflow/node metadata value
 * @param limit maximum result count between 1 and 200
 */
public record WorkflowSemanticHistoryQuery(
    String branch,
    String workflowId,
    String nodeId,
    String nodeType,
    String labelText,
    String propertyKey,
    String propertyValue,
    int limit) {

  public WorkflowSemanticHistoryQuery {
    WorkflowSemanticHistoryFilter filter =
        new WorkflowSemanticHistoryFilter(
            branch, workflowId, nodeId, nodeType, labelText, propertyKey, propertyValue);
    branch = filter.branch();
    workflowId = filter.workflowId();
    nodeId = filter.nodeId();
    nodeType = filter.nodeType();
    labelText = filter.labelText();
    propertyKey = filter.propertyKey();
    propertyValue = filter.propertyValue();
    if (limit <= 0 || limit > 200) {
      throw new IllegalArgumentException("limit must be between 1 and 200");
    }
  }

  /** Returns the unbounded semantic filter represented by this bounded query. */
  public WorkflowSemanticHistoryFilter filter() {
    return new WorkflowSemanticHistoryFilter(
        branch, workflowId, nodeId, nodeType, labelText, propertyKey, propertyValue);
  }
}
