package org.hammer.audio.workflow.merge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.merge.WorkflowDiffModels.Change;
import org.hammer.audio.workflow.merge.WorkflowDiffModels.ChangeKind;
import org.hammer.audio.workflow.merge.WorkflowDiffModels.Diff;
import org.hammer.audio.workflow.merge.WorkflowDiffModels.ElementKind;

/** Compares workflow values by stable semantic identities rather than serialized line positions. */
public final class WorkflowSemanticDiffer {

  /** Returns the deterministic semantic difference from {@code before} to {@code after}. */
  public Diff diff(Workflow before, Workflow after) {
    Objects.requireNonNull(before, "before");
    Objects.requireNonNull(after, "after");
    List<Change> changes = new ArrayList<>();
    addModified(
        changes,
        ElementKind.WORKFLOW,
        before.id(),
        "name",
        before.name(),
        after.name());
    diffMetadata(
        changes,
        ElementKind.WORKFLOW,
        before.id(),
        before.metadata(),
        after.metadata());
    diffNodes(changes, before.nodes(), after.nodes());
    diffEdges(changes, before.edges(), after.edges());
    changes.sort(Change.ORDERING);
    return new Diff(before.id(), after.id(), changes);
  }

  private static void diffNodes(List<Change> changes, List<Node> before, List<Node> after) {
    Map<String, Node> beforeById = indexNodes(before);
    Map<String, Node> afterById = indexNodes(after);
    for (String nodeId : union(beforeById, afterById)) {
      Node previous = beforeById.get(nodeId);
      Node next = afterById.get(nodeId);
      if (previous == null) {
        changes.add(
            new Change(
                ElementKind.NODE,
                nodeId,
                "$object",
                ChangeKind.ADDED,
                null,
                WorkflowSemanticValues.node(next)));
      } else if (next == null) {
        changes.add(
            new Change(
                ElementKind.NODE,
                nodeId,
                "$object",
                ChangeKind.REMOVED,
                WorkflowSemanticValues.node(previous),
                null));
      } else {
        diffNode(changes, previous, next);
      }
    }
  }

  private static void diffNode(List<Change> changes, Node before, Node after) {
    addModified(changes, ElementKind.NODE, before.id(), "type", before.type(), after.type());
    addModified(changes, ElementKind.NODE, before.id(), "label", before.label(), after.label());
    addModified(
        changes,
        ElementKind.NODE,
        before.id(),
        "inputPorts",
        WorkflowSemanticValues.ports(before.inputPorts()),
        WorkflowSemanticValues.ports(after.inputPorts()));
    addModified(
        changes,
        ElementKind.NODE,
        before.id(),
        "outputPorts",
        WorkflowSemanticValues.ports(before.outputPorts()),
        WorkflowSemanticValues.ports(after.outputPorts()));
    diffMetadata(changes, ElementKind.NODE, before.id(), before.metadata(), after.metadata());
  }

  private static void diffEdges(List<Change> changes, List<Edge> before, List<Edge> after) {
    Map<String, Edge> beforeById = indexEdges(before);
    Map<String, Edge> afterById = indexEdges(after);
    for (String edgeId : union(beforeById, afterById)) {
      Edge previous = beforeById.get(edgeId);
      Edge next = afterById.get(edgeId);
      if (previous == null) {
        changes.add(
            new Change(
                ElementKind.EDGE,
                edgeId,
                "$object",
                ChangeKind.ADDED,
                null,
                WorkflowSemanticValues.edge(next)));
      } else if (next == null) {
        changes.add(
            new Change(
                ElementKind.EDGE,
                edgeId,
                "$object",
                ChangeKind.REMOVED,
                WorkflowSemanticValues.edge(previous),
                null));
      } else {
        diffEdge(changes, previous, next);
      }
    }
  }

  private static void diffEdge(List<Change> changes, Edge before, Edge after) {
    addModified(
        changes,
        ElementKind.EDGE,
        before.id(),
        "sourceNodeId",
        before.sourceNodeId(),
        after.sourceNodeId());
    addModified(
        changes,
        ElementKind.EDGE,
        before.id(),
        "sourcePortId",
        before.sourcePortId(),
        after.sourcePortId());
    addModified(
        changes,
        ElementKind.EDGE,
        before.id(),
        "targetNodeId",
        before.targetNodeId(),
        after.targetNodeId());
    addModified(
        changes,
        ElementKind.EDGE,
        before.id(),
        "targetPortId",
        before.targetPortId(),
        after.targetPortId());
    diffMetadata(changes, ElementKind.EDGE, before.id(), before.metadata(), after.metadata());
  }

  private static void diffMetadata(
      List<Change> changes,
      ElementKind elementKind,
      String elementId,
      Metadata before,
      Metadata after) {
    for (String key : union(before.entries(), after.entries())) {
      String previous = before.entries().get(key);
      String next = after.entries().get(key);
      if (Objects.equals(previous, next)) {
        continue;
      }
      ChangeKind kind =
          previous == null
              ? ChangeKind.ADDED
              : next == null ? ChangeKind.REMOVED : ChangeKind.MODIFIED;
      changes.add(
          new Change(elementKind, elementId, "metadata." + key, kind, previous, next));
    }
  }

  private static void addModified(
      List<Change> changes,
      ElementKind elementKind,
      String elementId,
      String fieldPath,
      String before,
      String after) {
    if (!Objects.equals(before, after)) {
      changes.add(
          new Change(
              elementKind, elementId, fieldPath, ChangeKind.MODIFIED, before, after));
    }
  }

  private static Map<String, Node> indexNodes(List<Node> nodes) {
    Map<String, Node> indexed = new TreeMap<>();
    for (Node node : nodes) {
      if (indexed.putIfAbsent(node.id(), node) != null) {
        throw new IllegalArgumentException("Duplicate node id: " + node.id());
      }
    }
    return indexed;
  }

  private static Map<String, Edge> indexEdges(List<Edge> edges) {
    Map<String, Edge> indexed = new TreeMap<>();
    for (Edge edge : edges) {
      if (indexed.putIfAbsent(edge.id(), edge) != null) {
        throw new IllegalArgumentException("Duplicate edge id: " + edge.id());
      }
    }
    return indexed;
  }

  private static <T> List<String> union(Map<String, T> left, Map<String, T> right) {
    TreeSet<String> keys = new TreeSet<>(left.keySet());
    keys.addAll(right.keySet());
    return List.copyOf(keys);
  }
}
