package org.hammer.audio.workflow.version;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowValidator;

/** Deterministic three-way semantic merge for workflow nodes and edges. */
public final class WorkflowMergeService {

  private final WorkflowValidator validator = new WorkflowValidator();

  public MergeResult merge(Workflow base, Workflow local, Workflow remote) {
    return merge(base, local, remote, Map.of());
  }

  public MergeResult merge(
      Workflow base,
      Workflow local,
      Workflow remote,
      Map<String, WorkflowMergeResolution.Choice> resolutions) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(local, "local");
    Objects.requireNonNull(remote, "remote");
    Objects.requireNonNull(resolutions, "resolutions");

    List<WorkflowMergeConflict> conflicts = new ArrayList<>();
    Map<String, Node> nodes =
        mergeObjects(
            "node",
            index(base.nodes(), Node::id),
            index(local.nodes(), Node::id),
            index(remote.nodes(), Node::id),
            conflicts,
            resolutions,
            WorkflowMergeConflict.Type.NODE_CHANGED_DIFFERENTLY);
    Map<String, Edge> edges =
        mergeObjects(
            "edge",
            index(base.edges(), Edge::id),
            index(local.edges(), Edge::id),
            index(remote.edges(), Edge::id),
            conflicts,
            resolutions,
            WorkflowMergeConflict.Type.EDGE_CHANGED_DIFFERENTLY);

    detectDeleteVsConnect(base, local, remote, nodes, conflicts, resolutions);
    List<WorkflowMergeConflict> unresolved =
        conflicts.stream()
            .filter(conflict -> !resolutions.containsKey(conflict.conflictId()))
            .toList();
    if (!unresolved.isEmpty()) {
      return new MergeResult(null, conflicts, validatorMessages(List.of()), false);
    }
    String name = mergeScalar(base.name(), local.name(), remote.name());
    Workflow merged =
        new Workflow(
            local.id(),
            name,
            nodes.values().stream().sorted(Comparator.comparing(Node::id)).toList(),
            edges.values().stream().sorted(Comparator.comparing(Edge::id)).toList());
    List<String> violations = validator.validate(merged);
    return new MergeResult(
        violations.isEmpty() ? merged : null, conflicts, violations, violations.isEmpty());
  }

  private static <T> Map<String, T> mergeObjects(
      String prefix,
      Map<String, T> base,
      Map<String, T> local,
      Map<String, T> remote,
      List<WorkflowMergeConflict> conflicts,
      Map<String, WorkflowMergeResolution.Choice> resolutions,
      WorkflowMergeConflict.Type changedType) {
    Map<String, T> merged = new LinkedHashMap<>();
    List<String> ids =
        java.util.stream.Stream.concat(
                java.util.stream.Stream.concat(base.keySet().stream(), local.keySet().stream()),
                remote.keySet().stream())
            .distinct()
            .sorted()
            .toList();
    for (String id : ids) {
      T baseValue = base.get(id);
      T localValue = local.get(id);
      T remoteValue = remote.get(id);
      T selected;
      if (Objects.equals(localValue, remoteValue)) {
        selected = localValue;
      } else if (Objects.equals(baseValue, localValue)) {
        selected = remoteValue;
      } else if (Objects.equals(baseValue, remoteValue)) {
        selected = localValue;
      } else {
        WorkflowMergeConflict.Type type =
            baseValue != null && (localValue == null || remoteValue == null)
                ? WorkflowMergeConflict.Type.DELETE_VS_MODIFY
                : changedType;
        if (baseValue == null && localValue != null && remoteValue != null) {
          type = WorkflowMergeConflict.Type.IDENTIFIER_COLLISION;
        }
        String conflictId = prefix + ":" + id;
        WorkflowMergeConflict conflict =
            new WorkflowMergeConflict(
                conflictId,
                type,
                id,
                prefix,
                stringify(baseValue),
                stringify(localValue),
                stringify(remoteValue),
                "Both branches changed " + prefix + " '" + id + "' differently");
        conflicts.add(conflict);
        selected = select(resolutions.get(conflictId), baseValue, localValue, remoteValue);
      }
      if (selected != null) {
        merged.put(id, selected);
      }
    }
    return merged;
  }

  private static void detectDeleteVsConnect(
      Workflow base,
      Workflow local,
      Workflow remote,
      Map<String, Node> mergedNodes,
      List<WorkflowMergeConflict> conflicts,
      Map<String, WorkflowMergeResolution.Choice> resolutions) {
    Map<String, Node> baseNodes = index(base.nodes(), Node::id);
    Map<String, Node> localNodes = index(local.nodes(), Node::id);
    Map<String, Node> remoteNodes = index(remote.nodes(), Node::id);
    List<Edge> branchEdges =
        java.util.stream.Stream.concat(local.edges().stream(), remote.edges().stream())
            .distinct()
            .sorted(Comparator.comparing(Edge::id))
            .toList();
    for (Edge edge : branchEdges) {
      for (String nodeId : List.of(edge.sourceNodeId(), edge.targetNodeId())) {
        boolean deletedLocally = baseNodes.containsKey(nodeId) && !localNodes.containsKey(nodeId);
        boolean deletedRemotely = baseNodes.containsKey(nodeId) && !remoteNodes.containsKey(nodeId);
        boolean connectedByOther =
            (deletedLocally && remote.edges().contains(edge))
                || (deletedRemotely && local.edges().contains(edge));
        if (connectedByOther && !mergedNodes.containsKey(nodeId)) {
          String id = "delete-connect:" + nodeId + ":" + edge.id();
          if (conflicts.stream().noneMatch(conflict -> conflict.conflictId().equals(id))) {
            conflicts.add(
                new WorkflowMergeConflict(
                    id,
                    WorkflowMergeConflict.Type.DELETE_VS_CONNECT,
                    nodeId,
                    "edge",
                    null,
                    edge.toString(),
                    edge.toString(),
                    "Node '"
                        + nodeId
                        + "' was deleted while edge '"
                        + edge.id()
                        + "' connects it"));
          }
        }
      }
    }
  }

  private static <T> T select(WorkflowMergeResolution.Choice choice, T base, T local, T remote) {
    if (choice == null) {
      return null;
    }
    return switch (choice) {
      case BASE -> base;
      case LOCAL -> local;
      case REMOTE -> remote;
      case DELETE -> null;
    };
  }

  private static String mergeScalar(String base, String local, String remote) {
    if (Objects.equals(local, remote)) {
      return local;
    }
    if (Objects.equals(base, local)) {
      return remote;
    }
    return local;
  }

  private static <T> Map<String, T> index(List<T> values, Function<T, String> id) {
    Map<String, T> result = new LinkedHashMap<>();
    values.stream()
        .sorted(Comparator.comparing(id))
        .forEach(value -> result.put(id.apply(value), value));
    return result;
  }

  private static String stringify(Object value) {
    return value == null ? null : value.toString();
  }

  private static List<String> validatorMessages(List<String> messages) {
    return List.copyOf(messages);
  }

  public record MergeResult(
      Workflow mergedWorkflow,
      List<WorkflowMergeConflict> conflicts,
      List<String> validationViolations,
      boolean resolved) {
    public MergeResult {
      conflicts = List.copyOf(conflicts);
      validationViolations = List.copyOf(validationViolations);
    }
  }
}
