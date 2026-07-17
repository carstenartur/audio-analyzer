package org.hammer.audio.workflow.version;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;

/** Framework-independent semantic graph diff. */
public final class WorkflowSemanticDiffService {

  public WorkflowDiff compare(Workflow left, Workflow right) {
    Objects.requireNonNull(left, "left");
    Objects.requireNonNull(right, "right");
    List<WorkflowDiff.NodeChange> nodeChanges =
        compareNodes(indexNodes(left.nodes()), indexNodes(right.nodes()));
    List<WorkflowDiff.EdgeChange> edgeChanges =
        compareEdges(indexEdges(left.edges()), indexEdges(right.edges()));
    return new WorkflowDiff(
        left.id(), right.id(), nodeChanges, edgeChanges, !left.name().equals(right.name()));
  }

  private static List<WorkflowDiff.NodeChange> compareNodes(
      Map<String, Node> left, Map<String, Node> right) {
    List<String> ids = union(left, right);
    List<WorkflowDiff.NodeChange> changes = new ArrayList<>();
    for (String id : ids) {
      Node before = left.get(id);
      Node after = right.get(id);
      if (before == null) {
        changes.add(new WorkflowDiff.NodeChange(id, WorkflowDiff.ChangeKind.ADDED, null, after));
      } else if (after == null) {
        changes.add(new WorkflowDiff.NodeChange(id, WorkflowDiff.ChangeKind.REMOVED, before, null));
      } else if (!before.equals(after)) {
        changes.add(
            new WorkflowDiff.NodeChange(id, WorkflowDiff.ChangeKind.MODIFIED, before, after));
      }
    }
    return changes;
  }

  private static List<WorkflowDiff.EdgeChange> compareEdges(
      Map<String, Edge> left, Map<String, Edge> right) {
    List<String> ids = union(left, right);
    List<WorkflowDiff.EdgeChange> changes = new ArrayList<>();
    for (String id : ids) {
      Edge before = left.get(id);
      Edge after = right.get(id);
      if (before == null) {
        changes.add(new WorkflowDiff.EdgeChange(id, WorkflowDiff.ChangeKind.ADDED, null, after));
      } else if (after == null) {
        changes.add(new WorkflowDiff.EdgeChange(id, WorkflowDiff.ChangeKind.REMOVED, before, null));
      } else if (!before.equals(after)) {
        changes.add(
            new WorkflowDiff.EdgeChange(id, WorkflowDiff.ChangeKind.MODIFIED, before, after));
      }
    }
    return changes;
  }

  private static Map<String, Node> indexNodes(List<Node> nodes) {
    Map<String, Node> result = new LinkedHashMap<>();
    nodes.stream()
        .sorted(Comparator.comparing(Node::id))
        .forEach(node -> result.put(node.id(), node));
    return result;
  }

  private static Map<String, Edge> indexEdges(List<Edge> edges) {
    Map<String, Edge> result = new LinkedHashMap<>();
    edges.stream()
        .sorted(Comparator.comparing(Edge::id))
        .forEach(edge -> result.put(edge.id(), edge));
    return result;
  }

  private static <T> List<String> union(Map<String, T> left, Map<String, T> right) {
    return java.util.stream.Stream.concat(left.keySet().stream(), right.keySet().stream())
        .distinct()
        .sorted()
        .toList();
  }
}
