package org.hammer.audio.workflow.history;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowSemanticValueFormatter;
import org.hammer.audio.workflow.history.WorkflowChange.ElementKind;

/**
 * Semantic diff between two {@link Workflow} snapshots.
 *
 * <p>A {@code WorkflowDiff} holds the ordered list of {@link WorkflowChange} atoms that represent
 * the domain-level difference between a <em>before</em> and an <em>after</em> snapshot. Changes are
 * based on stable workflow, node and edge identifiers rather than serialized line positions.
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
   * Computes the complete semantic diff between two workflow snapshots.
   *
   * <p>Whole-object additions/removals retain the established change variants. Metadata changes on
   * existing nodes and edges retain {@link WorkflowChange.ParameterChanged} for transport
   * compatibility. Workflow name/metadata, node type/label/ports and edge endpoints use {@link
   * WorkflowChange.FieldChanged} with canonical values.
   *
   * @param before workflow snapshot before the change
   * @param after workflow snapshot after the change
   * @return deterministic domain-level changes
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
    collectWorkflowFieldChanges(before, after, changes);
    collectNodeFieldChanges(beforeNodes, afterNodes, changes);
    collectEdgeFieldChanges(beforeEdges, afterEdges, changes);
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

  private static void collectWorkflowFieldChanges(
      Workflow before, Workflow after, List<WorkflowChange> changes) {
    addFieldChange(changes, ElementKind.WORKFLOW, before.id(), "name", before.name(), after.name());
    diffWorkflowMetadata(before.id(), before.metadata(), after.metadata(), changes);
  }

  private static void collectNodeFieldChanges(
      Map<String, Node> beforeNodes, Map<String, Node> afterNodes, List<WorkflowChange> changes) {
    for (Map.Entry<String, Node> entry : afterNodes.entrySet()) {
      Node before = beforeNodes.get(entry.getKey());
      if (before == null) {
        continue;
      }
      Node after = entry.getValue();
      addFieldChange(changes, ElementKind.NODE, before.id(), "type", before.type(), after.type());
      addFieldChange(
          changes, ElementKind.NODE, before.id(), "label", before.label(), after.label());
      addFieldChange(
          changes,
          ElementKind.NODE,
          before.id(),
          "inputPorts",
          WorkflowSemanticValueFormatter.ports(before.inputPorts()),
          WorkflowSemanticValueFormatter.ports(after.inputPorts()));
      addFieldChange(
          changes,
          ElementKind.NODE,
          before.id(),
          "outputPorts",
          WorkflowSemanticValueFormatter.ports(before.outputPorts()),
          WorkflowSemanticValueFormatter.ports(after.outputPorts()));
      diffLegacyMetadata(
          before.id(), before.metadata().entries(), after.metadata().entries(), changes);
    }
  }

  private static void collectEdgeFieldChanges(
      Map<String, Edge> beforeEdges, Map<String, Edge> afterEdges, List<WorkflowChange> changes) {
    for (Map.Entry<String, Edge> entry : afterEdges.entrySet()) {
      Edge before = beforeEdges.get(entry.getKey());
      if (before == null) {
        continue;
      }
      Edge after = entry.getValue();
      addFieldChange(
          changes,
          ElementKind.EDGE,
          before.id(),
          "endpoints",
          WorkflowSemanticValueFormatter.endpoints(before),
          WorkflowSemanticValueFormatter.endpoints(after));
      diffLegacyMetadata(
          before.id(), before.metadata().entries(), after.metadata().entries(), changes);
    }
  }

  private static void diffWorkflowMetadata(
      String workflowId, Metadata before, Metadata after, List<WorkflowChange> changes) {
    for (String key : sortedKeys(before.entries(), after.entries())) {
      addFieldChange(
          changes,
          ElementKind.WORKFLOW,
          workflowId,
          "metadata." + key,
          before.entries().get(key),
          after.entries().get(key));
    }
  }

  private static void diffLegacyMetadata(
      String targetId,
      Map<String, String> before,
      Map<String, String> after,
      List<WorkflowChange> changes) {
    for (String key : sortedKeys(before, after)) {
      String beforeValue = before.get(key);
      String afterValue = after.get(key);
      if (!Objects.equals(beforeValue, afterValue)) {
        changes.add(new WorkflowChange.ParameterChanged(targetId, key, beforeValue, afterValue));
      }
    }
  }

  private static void addFieldChange(
      List<WorkflowChange> changes,
      ElementKind kind,
      String targetId,
      String fieldPath,
      String before,
      String after) {
    if (!Objects.equals(before, after)) {
      changes.add(new WorkflowChange.FieldChanged(kind, targetId, fieldPath, before, after));
    }
  }

  private static List<String> sortedKeys(Map<String, String> before, Map<String, String> after) {
    Set<String> keys = new TreeSet<>(before.keySet());
    keys.addAll(after.keySet());
    return List.copyOf(keys);
  }

  private static Map<String, Node> indexNodes(Workflow workflow) {
    Map<String, Node> index = new TreeMap<>();
    for (Node node : workflow.nodes()) {
      if (index.putIfAbsent(node.id(), node) != null) {
        throw new IllegalArgumentException("Duplicate node id: " + node.id());
      }
    }
    return index;
  }

  private static Map<String, Edge> indexEdges(Workflow workflow) {
    Map<String, Edge> index = new TreeMap<>();
    for (Edge edge : workflow.edges()) {
      if (index.putIfAbsent(edge.id(), edge) != null) {
        throw new IllegalArgumentException("Duplicate edge id: " + edge.id());
      }
    }
    return index;
  }
}
