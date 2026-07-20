package org.hammer.audio.workflow.history;

import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.store.CommitId;

/**
 * Domain-semantic evidence tied to one exact authoritative workflow commit.
 *
 * @param commitId exact Git commit identity
 * @param branch branch for which the commit was projected as reachable
 * @param workflowId stable workflow identifier
 * @param workflowName human-readable workflow name
 * @param nodeIds stable node identifiers contained in the workflow
 * @param nodeTypes logical node types contained in the workflow
 * @param nodeLabels human-readable node labels contained in the workflow
 * @param propertyKeys workflow/node metadata keys contained in the workflow
 * @param propertyValues workflow/node metadata values contained in the workflow
 */
public record WorkflowSemanticHistoryResult(
    CommitId commitId,
    String branch,
    String workflowId,
    String workflowName,
    List<String> nodeIds,
    List<String> nodeTypes,
    List<String> nodeLabels,
    List<String> propertyKeys,
    List<String> propertyValues) {

  public WorkflowSemanticHistoryResult {
    Objects.requireNonNull(commitId, "commitId");
    branch = requireNotBlank(branch, "branch");
    workflowId = requireNotBlank(workflowId, "workflowId");
    workflowName = requireNotBlank(workflowName, "workflowName");
    nodeIds = List.copyOf(Objects.requireNonNull(nodeIds, "nodeIds"));
    nodeTypes = List.copyOf(Objects.requireNonNull(nodeTypes, "nodeTypes"));
    nodeLabels = List.copyOf(Objects.requireNonNull(nodeLabels, "nodeLabels"));
    propertyKeys = List.copyOf(Objects.requireNonNull(propertyKeys, "propertyKeys"));
    propertyValues = List.copyOf(Objects.requireNonNull(propertyValues, "propertyValues"));
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
