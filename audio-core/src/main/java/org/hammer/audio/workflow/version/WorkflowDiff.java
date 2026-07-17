package org.hammer.audio.workflow.version;

import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;

/** Deterministically ordered semantic comparison of two workflow graphs. */
public record WorkflowDiff(
    String leftWorkflowId,
    String rightWorkflowId,
    List<NodeChange> nodeChanges,
    List<EdgeChange> edgeChanges,
    boolean nameChanged) {

  public WorkflowDiff {
    Objects.requireNonNull(leftWorkflowId, "leftWorkflowId");
    Objects.requireNonNull(rightWorkflowId, "rightWorkflowId");
    nodeChanges = List.copyOf(nodeChanges);
    edgeChanges = List.copyOf(edgeChanges);
  }

  public enum ChangeKind {
    ADDED,
    REMOVED,
    MODIFIED
  }

  public record NodeChange(String nodeId, ChangeKind kind, Node before, Node after) {}

  public record EdgeChange(String edgeId, ChangeKind kind, Edge before, Edge after) {}
}
