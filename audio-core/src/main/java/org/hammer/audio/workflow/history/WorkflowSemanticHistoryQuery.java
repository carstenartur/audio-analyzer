package org.hammer.audio.workflow.history;

import java.util.Objects;

/**
 * Exact domain-semantic filters for one workflow branch.
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
    branch = requireNotBlank(branch, "branch");
    workflowId = normalize(workflowId);
    nodeId = normalize(nodeId);
    nodeType = normalize(nodeType);
    labelText = normalize(labelText);
    propertyKey = normalize(propertyKey);
    propertyValue = normalize(propertyValue);
    if (limit <= 0 || limit > 200) {
      throw new IllegalArgumentException("limit must be between 1 and 200");
    }
  }

  private static String normalize(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    String normalized = value.trim();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return normalized;
  }
}
