package org.hammer.audio.workflow.execution;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;

/**
 * Immutable execution plan derived from an {@link ExecutionSnapshot} by topologically sorting
 * workflow nodes.
 *
 * <p>The plan fixes the order in which nodes must be executed so that every node is processed only
 * after all its upstream dependencies have finished. An {@link IllegalArgumentException} is thrown
 * at construction time if the workflow graph contains a cycle.
 *
 * @param planId stable identifier for this plan
 * @param snapshotId identifier of the snapshot this plan was derived from
 * @param orderedNodeIds node identifiers in topological execution order (upstream nodes first)
 */
public record ExecutionPlan(String planId, String snapshotId, List<String> orderedNodeIds) {

  public ExecutionPlan {
    StableExecutionIds.requireStable(planId, "planId");
    StableExecutionIds.requireStable(snapshotId, "snapshotId");
    Objects.requireNonNull(orderedNodeIds, "orderedNodeIds");
    orderedNodeIds = List.copyOf(orderedNodeIds);
  }

  /**
   * Derives an execution plan from the given snapshot using topological ordering (Kahn's
   * algorithm).
   *
   * @param planId stable identifier for this plan
   * @param snapshot the execution snapshot to plan
   * @return execution plan with nodes ordered so every predecessor comes before its successors
   * @throws IllegalArgumentException if the workflow graph contains a cycle
   */
  public static ExecutionPlan of(String planId, ExecutionSnapshot snapshot) {
    Objects.requireNonNull(planId, "planId");
    Objects.requireNonNull(snapshot, "snapshot");
    List<String> ordered = topologicalSort(snapshot.nodes(), snapshot.edges());
    return new ExecutionPlan(planId, snapshot.snapshotId(), ordered);
  }

  private static List<String> topologicalSort(List<Node> nodes, List<Edge> edges) {
    Map<String, Integer> inDegree = new LinkedHashMap<>();
    Map<String, List<String>> successors = new LinkedHashMap<>();

    for (Node node : nodes) {
      inDegree.put(node.id(), 0);
      successors.put(node.id(), new ArrayList<>());
    }

    for (Edge edge : edges) {
      if (!inDegree.containsKey(edge.sourceNodeId())) {
        throw new IllegalArgumentException("Edge source node is not part of the snapshot: " + edge);
      }
      if (!inDegree.containsKey(edge.targetNodeId())) {
        throw new IllegalArgumentException("Edge target node is not part of the snapshot: " + edge);
      }
      inDegree.merge(edge.targetNodeId(), 1, Integer::sum);
      successors.get(edge.sourceNodeId()).add(edge.targetNodeId());
    }

    Queue<String> ready = new ArrayDeque<>();
    for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
      if (entry.getValue() == 0) {
        ready.add(entry.getKey());
      }
    }

    List<String> result = new ArrayList<>();
    while (!ready.isEmpty()) {
      String nodeId = ready.poll();
      result.add(nodeId);
      for (String successor : successors.getOrDefault(nodeId, List.of())) {
        int newDegree = inDegree.merge(successor, -1, Integer::sum);
        if (newDegree == 0) {
          ready.add(successor);
        }
      }
    }

    if (result.size() != nodes.size()) {
      throw new IllegalArgumentException(
          "Workflow graph contains a cycle; topological sort is not possible");
    }

    return result;
  }
}
