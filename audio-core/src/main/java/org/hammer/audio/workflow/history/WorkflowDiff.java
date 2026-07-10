package org.hammer.audio.workflow.history;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;

/**
 * Semantic diff between two {@link Workflow} snapshots.
 *
 * <p>A {@code WorkflowDiff} holds the ordered list of {@link WorkflowChange} atoms that represent
 * the domain-level difference between a <em>before</em> and an <em>after</em> snapshot. Changes are
 * reported in the following order: removed edges, removed nodes, added nodes, added edges, changed
 * parameters.
 *
 * <p>Use {@link #compute(Workflow, Workflow)} to create an instance. Both arguments must be
 * non-null; passing an empty workflow for one side is the correct way to represent a creation or
 * deletion diff.
 *
 * <p>Owned by the semantic-analysis/history-projection layer. Must not depend on UI, JGit or
 * execution internals.
 *
 * @param changes ordered, immutable list of semantic changes; empty when the two snapshots are
 *     semantically equivalent
 */
public record WorkflowDiff(List<WorkflowChange> changes) {

  public WorkflowDiff {
    changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
  }

  /** Returns {@code true} when the two snapshots were semantically equivalent. */
  public boolean isEmpty() {
    return changes.isEmpty();
  }

  /**
   * Computes the semantic diff between two workflow snapshots.
   *
   * <p>Node and edge identity is based on stable {@code id} fields. Metadata property diffs are
   * computed for nodes and edges that exist in both snapshots.
   *
   * @param before workflow snapshot before the change; must not be {@code null}
   * @param after workflow snapshot after the change; must not be {@code null}
   * @return diff capturing domain-level changes; never {@code null}
   */
  public static WorkflowDiff compute(Workflow before, Workflow after) {
    Objects.requireNonNull(before, "before");
    Objects.requireNonNull(after, "after");

    Map<String, Node> beforeNodes = indexNodes(before);
    Map<String, Node> afterNodes = indexNodes(after);
    Map<String, Edge> beforeEdges = indexEdges(before);
    Map<String, Edge> afterEdges = indexEdges(after);

    List<WorkflowChange> changes = new ArrayList<>();
    collectRemovedEdges(beforeEdges, afterEdges, changes);
    collectRemovedNodes(beforeNodes, afterNodes, changes);
    collectAddedNodes(beforeNodes, afterNodes, changes);
    collectAddedEdges(beforeEdges, afterEdges, changes);
    collectNodeParameterChanges(beforeNodes, afterNodes, changes);
    collectEdgeParameterChanges(beforeEdges, afterEdges, changes);
    return new WorkflowDiff(changes);
  }

  private static void collectRemovedEdges(
      Map<String, Edge> beforeEdges, Map<String, Edge> afterEdges, List<WorkflowChange> changes) {
    for (Map.Entry<String, Edge> entry : beforeEdges.entrySet()) {
      if (!afterEdges.containsKey(entry.getKey())) {
        changes.add(new WorkflowChange.EdgeRemoved(entry.getValue()));
      }
    }
  }

  private static void collectRemovedNodes(
      Map<String, Node> beforeNodes, Map<String, Node> afterNodes, List<WorkflowChange> changes) {
    for (Map.Entry<String, Node> entry : beforeNodes.entrySet()) {
      if (!afterNodes.containsKey(entry.getKey())) {
        changes.add(new WorkflowChange.NodeRemoved(entry.getValue()));
      }
    }
  }

  private static void collectAddedNodes(
      Map<String, Node> beforeNodes, Map<String, Node> afterNodes, List<WorkflowChange> changes) {
    for (Map.Entry<String, Node> entry : afterNodes.entrySet()) {
      if (!beforeNodes.containsKey(entry.getKey())) {
        changes.add(new WorkflowChange.NodeAdded(entry.getValue()));
      }
    }
  }

  private static void collectAddedEdges(
      Map<String, Edge> beforeEdges, Map<String, Edge> afterEdges, List<WorkflowChange> changes) {
    for (Map.Entry<String, Edge> entry : afterEdges.entrySet()) {
      if (!beforeEdges.containsKey(entry.getKey())) {
        changes.add(new WorkflowChange.EdgeAdded(entry.getValue()));
      }
    }
  }

  private static void collectNodeParameterChanges(
      Map<String, Node> beforeNodes, Map<String, Node> afterNodes, List<WorkflowChange> changes) {
    for (Map.Entry<String, Node> entry : afterNodes.entrySet()) {
      Node beforeNode = beforeNodes.get(entry.getKey());
      if (beforeNode != null) {
        diffMetadata(
            entry.getKey(),
            beforeNode.metadata().entries(),
            entry.getValue().metadata().entries(),
            changes);
      }
    }
  }

  private static void collectEdgeParameterChanges(
      Map<String, Edge> beforeEdges, Map<String, Edge> afterEdges, List<WorkflowChange> changes) {
    for (Map.Entry<String, Edge> entry : afterEdges.entrySet()) {
      Edge beforeEdge = beforeEdges.get(entry.getKey());
      if (beforeEdge != null) {
        diffMetadata(
            entry.getKey(),
            beforeEdge.metadata().entries(),
            entry.getValue().metadata().entries(),
            changes);
      }
    }
  }

  private static void diffMetadata(
      String targetId,
      Map<String, String> before,
      Map<String, String> after,
      List<WorkflowChange> changes) {
    diffChangedOrRemovedProperties(targetId, before, after, changes);
    diffNewProperties(targetId, before, after, changes);
  }

  private static void diffChangedOrRemovedProperties(
      String targetId,
      Map<String, String> before,
      Map<String, String> after,
      List<WorkflowChange> changes) {
    for (Map.Entry<String, String> entry : before.entrySet()) {
      String afterValue = after.get(entry.getKey());
      if (!entry.getValue().equals(afterValue)) {
        changes.add(
            new WorkflowChange.ParameterChanged(
                targetId, entry.getKey(), entry.getValue(), afterValue));
      }
    }
  }

  private static void diffNewProperties(
      String targetId,
      Map<String, String> before,
      Map<String, String> after,
      List<WorkflowChange> changes) {
    for (Map.Entry<String, String> entry : after.entrySet()) {
      if (!before.containsKey(entry.getKey())) {
        changes.add(
            new WorkflowChange.ParameterChanged(targetId, entry.getKey(), null, entry.getValue()));
      }
    }
  }

  private static Map<String, Node> indexNodes(Workflow workflow) {
    Map<String, Node> index = new LinkedHashMap<>();
    for (Node node : workflow.nodes()) {
      index.put(node.id(), node);
    }
    return index;
  }

  private static Map<String, Edge> indexEdges(Workflow workflow) {
    Map<String, Edge> index = new LinkedHashMap<>();
    for (Edge edge : workflow.edges()) {
      index.put(edge.id(), edge);
    }
    return index;
  }
}
