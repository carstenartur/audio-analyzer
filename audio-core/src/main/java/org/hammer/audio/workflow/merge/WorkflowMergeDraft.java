package org.hammer.audio.workflow.merge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Port;
import org.hammer.audio.workflow.Workflow;

/** Internal deterministic mutable draft used while calculating and resolving one merge. */
final class WorkflowMergeDraft {

  private final String workflowId;
  private final Map<String, Node> nodesById = new ConcurrentHashMap<>();
  private final Map<String, Edge> edgesById = new ConcurrentHashMap<>();
  private final Map<String, String> workflowMetadata = new ConcurrentHashMap<>();
  private String workflowName;

  private WorkflowMergeDraft(Workflow base) {
    workflowId = base.id();
    workflowName = base.name();
    workflowMetadata.putAll(base.metadata().entries());
    for (Node node : base.nodes()) {
      putNode(node);
    }
    for (Edge edge : base.edges()) {
      putEdge(edge);
    }
  }

  static WorkflowMergeDraft from(Workflow base) {
    return new WorkflowMergeDraft(Objects.requireNonNull(base, "base"));
  }

  Workflow workflow() {
    return new Workflow(
        workflowId,
        workflowName,
        nodesById.values().stream().sorted(Comparator.comparing(Node::id)).toList(),
        edgesById.values().stream().sorted(Comparator.comparing(Edge::id)).toList(),
        new Metadata(workflowMetadata));
  }

  void setWorkflowName(String value) {
    workflowName = requireText(value, "workflowName");
  }

  void setWorkflowMetadata(String key, String value) {
    setMetadata(workflowMetadata, key, value);
  }

  void putNode(Node node) {
    Node required = Objects.requireNonNull(node, "node");
    nodesById.put(required.id(), required);
  }

  void removeNode(String nodeId) {
    nodesById.remove(nodeId);
  }

  void setNodeType(String nodeId, String type) {
    updateNode(
        nodeId,
        node ->
            new Node(
                node.id(),
                requireText(type, "type"),
                node.label(),
                node.inputPorts(),
                node.outputPorts(),
                node.metadata()));
  }

  void setNodeLabel(String nodeId, String label) {
    updateNode(
        nodeId,
        node ->
            new Node(
                node.id(),
                node.type(),
                requireText(label, "label"),
                node.inputPorts(),
                node.outputPorts(),
                node.metadata()));
  }

  void setNodeInputPorts(String nodeId, List<Port> ports) {
    updateNode(
        nodeId,
        node ->
            new Node(
                node.id(),
                node.type(),
                node.label(),
                List.copyOf(ports),
                node.outputPorts(),
                node.metadata()));
  }

  void setNodeOutputPorts(String nodeId, List<Port> ports) {
    updateNode(
        nodeId,
        node ->
            new Node(
                node.id(),
                node.type(),
                node.label(),
                node.inputPorts(),
                List.copyOf(ports),
                node.metadata()));
  }

  void setNodeMetadata(String nodeId, String key, String value) {
    updateNode(
        nodeId,
        node -> {
          Map<String, String> entries = new ConcurrentHashMap<>(node.metadata().entries());
          setMetadata(entries, key, value);
          return new Node(
              node.id(),
              node.type(),
              node.label(),
              node.inputPorts(),
              node.outputPorts(),
              new Metadata(entries));
        });
  }

  void putEdge(Edge edge) {
    Edge required = Objects.requireNonNull(edge, "edge");
    edgesById.put(required.id(), required);
  }

  void removeEdge(String edgeId) {
    edgesById.remove(edgeId);
  }

  void setEdgeEndpoints(String edgeId, Edge source) {
    Objects.requireNonNull(source, "source");
    updateEdge(
        edgeId,
        edge ->
            new Edge(
                edge.id(),
                source.sourceNodeId(),
                source.sourcePortId(),
                source.targetNodeId(),
                source.targetPortId(),
                edge.metadata()));
  }

  void setEdgeMetadata(String edgeId, String key, String value) {
    updateEdge(
        edgeId,
        edge -> {
          Map<String, String> entries = new ConcurrentHashMap<>(edge.metadata().entries());
          setMetadata(entries, key, value);
          return new Edge(
              edge.id(),
              edge.sourceNodeId(),
              edge.sourcePortId(),
              edge.targetNodeId(),
              edge.targetPortId(),
              new Metadata(entries));
        });
  }

  void replaceNodeNeighborhood(
      String nodeId, Node node, Collection<Edge> selectedEdges, Set<String> relatedEdgeIds) {
    Objects.requireNonNull(nodeId, "nodeId");
    Objects.requireNonNull(selectedEdges, "selectedEdges");
    Objects.requireNonNull(relatedEdgeIds, "relatedEdgeIds");
    for (String edgeId : relatedEdgeIds) {
      edgesById.remove(edgeId);
    }
    if (node == null) {
      nodesById.remove(nodeId);
      return;
    }
    nodesById.put(nodeId, node);
    for (Edge edge : selectedEdges) {
      if (references(edge, nodeId)) {
        edgesById.put(edge.id(), edge);
      }
    }
  }

  private void updateNode(String nodeId, UnaryOperator<Node> update) {
    Node current = nodesById.get(nodeId);
    if (current == null) {
      throw new IllegalStateException("Merge draft has no node " + nodeId);
    }
    nodesById.put(nodeId, Objects.requireNonNull(update.apply(current), "updated node"));
  }

  private void updateEdge(String edgeId, UnaryOperator<Edge> update) {
    Edge current = edgesById.get(edgeId);
    if (current == null) {
      throw new IllegalStateException("Merge draft has no edge " + edgeId);
    }
    edgesById.put(edgeId, Objects.requireNonNull(update.apply(current), "updated edge"));
  }

  private static void setMetadata(Map<String, String> entries, String key, String value) {
    String requiredKey = requireText(key, "metadata key");
    if (value == null) {
      entries.remove(requiredKey);
    } else {
      entries.put(requiredKey, value);
    }
  }

  static List<Edge> connectedEdges(List<Edge> edges, String nodeId) {
    List<Edge> connected = new ArrayList<>();
    for (Edge edge : edges) {
      if (references(edge, nodeId)) {
        connected.add(edge);
      }
    }
    connected.sort(Comparator.comparing(Edge::id));
    return List.copyOf(connected);
  }

  static boolean references(Edge edge, String nodeId) {
    return edge.sourceNodeId().equals(nodeId) || edge.targetNodeId().equals(nodeId);
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
