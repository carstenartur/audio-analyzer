package org.hammer.audio.workflow.execution;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;

/**
 * Immutable point-in-time snapshot of a {@link Workflow} taken before execution starts.
 *
 * <p>The originating workflow may continue to evolve after the snapshot is taken; executions always
 * operate on this frozen copy. Multiple independent executions may reference the same snapshot.
 *
 * @param snapshotId stable identifier for this snapshot
 * @param workflowId identifier of the originating workflow
 * @param nodes immutable copy of workflow nodes at snapshot time
 * @param edges immutable copy of workflow edges at snapshot time
 * @param metadata workflow-level metadata captured at snapshot time
 * @param createdAt instant at which the snapshot was taken
 */
public record ExecutionSnapshot(
    String snapshotId,
    String workflowId,
    List<Node> nodes,
    List<Edge> edges,
    Metadata metadata,
    Instant createdAt) {

  public ExecutionSnapshot {
    Objects.requireNonNull(snapshotId, "snapshotId");
    if (snapshotId.isBlank()) {
      throw new IllegalArgumentException("snapshotId must not be blank");
    }
    Objects.requireNonNull(workflowId, "workflowId");
    if (workflowId.isBlank()) {
      throw new IllegalArgumentException("workflowId must not be blank");
    }
    Objects.requireNonNull(nodes, "nodes");
    Objects.requireNonNull(edges, "edges");
    Objects.requireNonNull(createdAt, "createdAt");
    nodes = List.copyOf(nodes);
    edges = List.copyOf(edges);
    metadata = metadata == null ? Metadata.empty() : metadata;
  }

  /**
   * Creates an execution snapshot from the given workflow.
   *
   * @param snapshotId stable identifier for this snapshot
   * @param workflow workflow to snapshot
   * @param createdAt instant at which the snapshot is taken
   * @return new immutable snapshot of the workflow
   */
  public static ExecutionSnapshot of(String snapshotId, Workflow workflow, Instant createdAt) {
    Objects.requireNonNull(snapshotId, "snapshotId");
    Objects.requireNonNull(workflow, "workflow");
    Objects.requireNonNull(createdAt, "createdAt");
    return new ExecutionSnapshot(
        snapshotId,
        workflow.id(),
        workflow.nodes(),
        workflow.edges(),
        workflow.metadata(),
        createdAt);
  }
}
