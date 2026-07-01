package org.hammer.audio.workflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Semantic workflow operation with deterministic apply and optional inverse operation. */
public sealed interface WorkflowOperation
    permits WorkflowOperation.CreateNode,
        WorkflowOperation.DeleteNode,
        WorkflowOperation.MoveNode,
        WorkflowOperation.RenameNode,
        WorkflowOperation.ConnectPorts,
        WorkflowOperation.DisconnectPorts,
        WorkflowOperation.UpdateProperty,
        WorkflowOperation.GroupNodes,
        WorkflowOperation.UngroupNodes,
        WorkflowOperation.RestoreNode {

  String NO_GROUP = "__none__";
  String UNDO_SUFFIX = ":undo";
  String NODE_ID = "nodeId";
  String WORKFLOW_FIELD = "workflow";

  String operationId();

  Instant timestamp();

  String author();

  List<String> affectedObjectIds();

  Map<String, String> payload();

  Optional<WorkflowOperation> inverseOperation();

  Workflow apply(Workflow workflow);

  static void requireOperationMetadata(
      String operationId, Instant timestamp, String author, String fieldName) {
    StableIds.requireStable(operationId, fieldName + ".operationId");
    Objects.requireNonNull(timestamp, fieldName + ".timestamp");
    if (author == null || author.isBlank()) {
      throw new IllegalArgumentException(fieldName + ".author must not be blank");
    }
  }

  static Node requireNode(Workflow workflow, String nodeId, String fieldName) {
    return workflow.nodes().stream()
        .filter(node -> node.id().equals(nodeId))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException(fieldName + " node not found: " + nodeId));
  }

  static Edge requireEdge(Workflow workflow, String edgeId, String fieldName) {
    return workflow.edges().stream()
        .filter(edge -> edge.id().equals(edgeId))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException(fieldName + " edge not found: " + edgeId));
  }

  static Metadata updateMetadata(Metadata metadata, String key, String value) {
    StableIds.requireStable(key, "property key");
    Map<String, String> entries = new ConcurrentHashMap<>(metadata.entries());
    if (value == null) {
      entries.remove(key);
    } else {
      entries.put(key, value);
    }
    return entries.isEmpty() ? Metadata.empty() : new Metadata(entries);
  }

  static Workflow replaceNode(Workflow workflow, Node replacementNode) {
    List<Node> nodes = new ArrayList<>(workflow.nodes().size());
    boolean replaced = false;
    for (Node node : workflow.nodes()) {
      if (node.id().equals(replacementNode.id())) {
        nodes.add(replacementNode);
        replaced = true;
      } else {
        nodes.add(node);
      }
    }
    if (!replaced) {
      throw new IllegalArgumentException("node not found: " + replacementNode.id());
    }
    return new Workflow(
        workflow.id(), workflow.name(), nodes, workflow.edges(), workflow.metadata());
  }

  static Map<String, String> requiredPreviousGroups(
      List<String> nodeIds, Map<String, String> previousGroupIds, String fieldName) {
    Objects.requireNonNull(previousGroupIds, fieldName + ".previousGroupIds");
    Map<String, String> normalizedGroups = new ConcurrentHashMap<>();
    for (String nodeId : nodeIds) {
      if (!previousGroupIds.containsKey(nodeId)) {
        throw new IllegalArgumentException(
            fieldName + ".previousGroupIds must contain node id " + nodeId);
      }
      String previousGroupId = previousGroupIds.get(nodeId);
      normalizedGroups.put(nodeId, previousGroupId == null ? NO_GROUP : previousGroupId);
    }
    return Map.copyOf(normalizedGroups);
  }

  /**
   * Operation that adds a new node to the workflow.
   *
   * @param operationId unique operation id
   * @param timestamp operation timestamp
   * @param author operation author
   * @param node node to add
   */
  record CreateNode(String operationId, Instant timestamp, String author, Node node)
      implements WorkflowOperation {

    public CreateNode {
      requireOperationMetadata(operationId, timestamp, author, "CreateNode");
      Objects.requireNonNull(node, "node");
    }

    @Override
    public List<String> affectedObjectIds() {
      return List.of(node.id());
    }

    @Override
    public Map<String, String> payload() {
      return Map.of(NODE_ID, node.id(), "nodeType", node.type(), "label", node.label());
    }

    @Override
    public Optional<WorkflowOperation> inverseOperation() {
      return Optional.of(
          new DeleteNode(
              operationId + UNDO_SUFFIX, timestamp, author, node, List.of(), List.of(node.id())));
    }

    @Override
    public Workflow apply(Workflow workflow) {
      Objects.requireNonNull(workflow, WORKFLOW_FIELD);
      Set<String> nodeIds = new LinkedHashSet<>();
      for (Node existingNode : workflow.nodes()) {
        nodeIds.add(existingNode.id());
      }
      if (nodeIds.contains(node.id())) {
        throw new IllegalArgumentException("node already exists: " + node.id());
      }
      List<Node> nodes = new ArrayList<>(workflow.nodes());
      nodes.add(node);
      return new Workflow(
          workflow.id(), workflow.name(), nodes, workflow.edges(), workflow.metadata());
    }
  }

  /**
   * Operation that removes a node and its related edges from the workflow.
   *
   * @param operationId unique operation id
   * @param timestamp operation timestamp
   * @param author operation author
   * @param deletedNode node snapshot to remove
   * @param deletedEdges edge snapshots to remove
   * @param affectedObjectIds affected node and edge ids
   */
  record DeleteNode(
      String operationId,
      Instant timestamp,
      String author,
      Node deletedNode,
      List<Edge> deletedEdges,
      List<String> affectedObjectIds)
      implements WorkflowOperation {

    public DeleteNode {
      requireOperationMetadata(operationId, timestamp, author, "DeleteNode");
      Objects.requireNonNull(deletedNode, "deletedNode");
      Objects.requireNonNull(deletedEdges, "deletedEdges");
      deletedEdges = List.copyOf(deletedEdges);
      Objects.requireNonNull(affectedObjectIds, "affectedObjectIds");
      affectedObjectIds = List.copyOf(affectedObjectIds);
    }

    @Override
    public Map<String, String> payload() {
      return Map.of(
          NODE_ID, deletedNode.id(), "deletedEdgeCount", String.valueOf(deletedEdges.size()));
    }

    @Override
    public Optional<WorkflowOperation> inverseOperation() {
      return Optional.of(
          new RestoreNode(operationId + UNDO_SUFFIX, timestamp, author, deletedNode, deletedEdges));
    }

    @Override
    public Workflow apply(Workflow workflow) {
      Objects.requireNonNull(workflow, WORKFLOW_FIELD);
      requireNode(workflow, deletedNode.id(), "DeleteNode");

      List<Node> nodes =
          workflow.nodes().stream().filter(node -> !node.id().equals(deletedNode.id())).toList();
      Set<String> deletedEdgeIds = new LinkedHashSet<>();
      for (Edge edge : deletedEdges) {
        deletedEdgeIds.add(edge.id());
      }
      List<Edge> edges = new ArrayList<>();
      for (Edge edge : workflow.edges()) {
        if (!edge.sourceNodeId().equals(deletedNode.id())
            && !edge.targetNodeId().equals(deletedNode.id())
            && !deletedEdgeIds.contains(edge.id())) {
          edges.add(edge);
        }
      }
      return new Workflow(workflow.id(), workflow.name(), nodes, edges, workflow.metadata());
    }
  }

  /**
   * Operation that updates node layout coordinates in metadata.
   *
   * @param operationId unique operation id
   * @param timestamp operation timestamp
   * @param author operation author
   * @param nodeId target node id
   * @param fromX previous x coordinate
   * @param fromY previous y coordinate
   * @param toX new x coordinate
   * @param toY new y coordinate
   */
  record MoveNode(
      String operationId,
      Instant timestamp,
      String author,
      String nodeId,
      double fromX,
      double fromY,
      double toX,
      double toY)
      implements WorkflowOperation {

    private static final String X_KEY = "layout.x";
    private static final String Y_KEY = "layout.y";

    public MoveNode {
      requireOperationMetadata(operationId, timestamp, author, "MoveNode");
      StableIds.requireStable(nodeId, "MoveNode.nodeId");
    }

    @Override
    public List<String> affectedObjectIds() {
      return List.of(nodeId);
    }

    @Override
    public Map<String, String> payload() {
      return Map.of(
          NODE_ID,
          nodeId,
          "fromX",
          String.valueOf(fromX),
          "fromY",
          String.valueOf(fromY),
          "toX",
          String.valueOf(toX),
          "toY",
          String.valueOf(toY));
    }

    @Override
    public Optional<WorkflowOperation> inverseOperation() {
      return Optional.of(
          new MoveNode(
              operationId + UNDO_SUFFIX, timestamp, author, nodeId, toX, toY, fromX, fromY));
    }

    @Override
    public Workflow apply(Workflow workflow) {
      Objects.requireNonNull(workflow, WORKFLOW_FIELD);
      Node node = requireNode(workflow, nodeId, "MoveNode");
      Metadata movedMetadata =
          updateMetadata(
              updateMetadata(node.metadata(), X_KEY, String.valueOf(toX)),
              Y_KEY,
              String.valueOf(toY));
      return replaceNode(
          workflow,
          new Node(
              node.id(),
              node.type(),
              node.label(),
              node.inputPorts(),
              node.outputPorts(),
              movedMetadata));
    }
  }

  /**
   * Operation that renames a node label.
   *
   * @param operationId unique operation id
   * @param timestamp operation timestamp
   * @param author operation author
   * @param nodeId target node id
   * @param previousLabel expected previous label
   * @param newLabel new label
   */
  record RenameNode(
      String operationId,
      Instant timestamp,
      String author,
      String nodeId,
      String previousLabel,
      String newLabel)
      implements WorkflowOperation {

    public RenameNode {
      requireOperationMetadata(operationId, timestamp, author, "RenameNode");
      StableIds.requireStable(nodeId, "RenameNode.nodeId");
      if (previousLabel == null || previousLabel.isBlank()) {
        throw new IllegalArgumentException("RenameNode.previousLabel must not be blank");
      }
      if (newLabel == null || newLabel.isBlank()) {
        throw new IllegalArgumentException("RenameNode.newLabel must not be blank");
      }
    }

    @Override
    public List<String> affectedObjectIds() {
      return List.of(nodeId);
    }

    @Override
    public Map<String, String> payload() {
      return Map.of(NODE_ID, nodeId, "from", previousLabel, "to", newLabel);
    }

    @Override
    public Optional<WorkflowOperation> inverseOperation() {
      return Optional.of(
          new RenameNode(
              operationId + UNDO_SUFFIX, timestamp, author, nodeId, newLabel, previousLabel));
    }

    @Override
    public Workflow apply(Workflow workflow) {
      Objects.requireNonNull(workflow, WORKFLOW_FIELD);
      Node node = requireNode(workflow, nodeId, "RenameNode");
      if (!node.label().equals(previousLabel)) {
        throw new IllegalStateException(
            "RenameNode expected label " + previousLabel + " but was " + node.label());
      }
      return replaceNode(
          workflow,
          new Node(
              node.id(),
              node.type(),
              newLabel,
              node.inputPorts(),
              node.outputPorts(),
              node.metadata()));
    }
  }

  /**
   * Operation that creates an edge connection between ports.
   *
   * @param operationId unique operation id
   * @param timestamp operation timestamp
   * @param author operation author
   * @param edge edge to create
   */
  record ConnectPorts(String operationId, Instant timestamp, String author, Edge edge)
      implements WorkflowOperation {

    public ConnectPorts {
      requireOperationMetadata(operationId, timestamp, author, "ConnectPorts");
      Objects.requireNonNull(edge, "edge");
    }

    @Override
    public List<String> affectedObjectIds() {
      return List.of(edge.sourceNodeId(), edge.targetNodeId(), edge.id());
    }

    @Override
    public Map<String, String> payload() {
      return Map.of(
          "edgeId",
          edge.id(),
          "source",
          edge.sourceNodeId() + ":" + edge.sourcePortId(),
          "target",
          edge.targetNodeId() + ":" + edge.targetPortId());
    }

    @Override
    public Optional<WorkflowOperation> inverseOperation() {
      return Optional.of(
          new DisconnectPorts(operationId + UNDO_SUFFIX, timestamp, author, edge.id(), edge));
    }

    @Override
    public Workflow apply(Workflow workflow) {
      Objects.requireNonNull(workflow, WORKFLOW_FIELD);
      requireNode(workflow, edge.sourceNodeId(), "ConnectPorts");
      requireNode(workflow, edge.targetNodeId(), "ConnectPorts");
      boolean edgeExists =
          workflow.edges().stream().anyMatch(existingEdge -> existingEdge.id().equals(edge.id()));
      if (edgeExists) {
        throw new IllegalArgumentException("edge already exists: " + edge.id());
      }
      List<Edge> edges = new ArrayList<>(workflow.edges());
      edges.add(edge);
      return new Workflow(
          workflow.id(), workflow.name(), workflow.nodes(), edges, workflow.metadata());
    }
  }

  /**
   * Operation that removes an existing edge connection.
   *
   * @param operationId unique operation id
   * @param timestamp operation timestamp
   * @param author operation author
   * @param edgeId edge id to remove
   * @param disconnectedEdge edge snapshot used for undo
   */
  record DisconnectPorts(
      String operationId, Instant timestamp, String author, String edgeId, Edge disconnectedEdge)
      implements WorkflowOperation {

    public DisconnectPorts {
      requireOperationMetadata(operationId, timestamp, author, "DisconnectPorts");
      StableIds.requireStable(edgeId, "DisconnectPorts.edgeId");
      Objects.requireNonNull(disconnectedEdge, "disconnectedEdge");
      if (!edgeId.equals(disconnectedEdge.id())) {
        throw new IllegalArgumentException("DisconnectPorts.edgeId must match disconnectedEdge.id");
      }
    }

    @Override
    public List<String> affectedObjectIds() {
      return List.of(disconnectedEdge.sourceNodeId(), disconnectedEdge.targetNodeId(), edgeId);
    }

    @Override
    public Map<String, String> payload() {
      return Map.of("edgeId", edgeId);
    }

    @Override
    public Optional<WorkflowOperation> inverseOperation() {
      return Optional.of(
          new ConnectPorts(operationId + UNDO_SUFFIX, timestamp, author, disconnectedEdge));
    }

    @Override
    public Workflow apply(Workflow workflow) {
      Objects.requireNonNull(workflow, WORKFLOW_FIELD);
      requireEdge(workflow, edgeId, "DisconnectPorts");
      List<Edge> edges =
          workflow.edges().stream().filter(edge -> !edge.id().equals(edgeId)).toList();
      return new Workflow(
          workflow.id(), workflow.name(), workflow.nodes(), edges, workflow.metadata());
    }
  }

  /**
   * Operation that updates metadata properties on workflow, node or edge targets.
   *
   * @param operationId unique operation id
   * @param timestamp operation timestamp
   * @param author operation author
   * @param target target object type
   * @param targetId target object id
   * @param propertyKey metadata key
   * @param previousValue previous property value
   * @param newValue new property value
   */
  record UpdateProperty(
      String operationId,
      Instant timestamp,
      String author,
      PropertyTarget target,
      String targetId,
      String propertyKey,
      String previousValue,
      String newValue)
      implements WorkflowOperation {

    public UpdateProperty {
      requireOperationMetadata(operationId, timestamp, author, "UpdateProperty");
      Objects.requireNonNull(target, "target");
      StableIds.requireStable(targetId, "UpdateProperty.targetId");
      StableIds.requireStable(propertyKey, "UpdateProperty.propertyKey");
    }

    @Override
    public List<String> affectedObjectIds() {
      return List.of(targetId);
    }

    @Override
    public Map<String, String> payload() {
      Map<String, String> payload = new ConcurrentHashMap<>();
      payload.put("target", target.name());
      payload.put("targetId", targetId);
      payload.put("propertyKey", propertyKey);
      payload.put("newValue", String.valueOf(newValue));
      payload.put("previousValue", String.valueOf(previousValue));
      return Map.copyOf(payload);
    }

    @Override
    public Optional<WorkflowOperation> inverseOperation() {
      return Optional.of(
          new UpdateProperty(
              operationId + UNDO_SUFFIX,
              timestamp,
              author,
              target,
              targetId,
              propertyKey,
              newValue,
              previousValue));
    }

    @Override
    public Workflow apply(Workflow workflow) {
      Objects.requireNonNull(workflow, WORKFLOW_FIELD);
      return switch (target) {
        case WORKFLOW -> {
          if (!workflow.id().equals(targetId)) {
            throw new IllegalArgumentException(
                "workflow id mismatch for UpdateProperty: " + targetId);
          }
          Metadata metadata = updateMetadata(workflow.metadata(), propertyKey, newValue);
          yield new Workflow(
              workflow.id(), workflow.name(), workflow.nodes(), workflow.edges(), metadata);
        }
        case NODE -> {
          Node node = requireNode(workflow, targetId, "UpdateProperty");
          Metadata metadata = updateMetadata(node.metadata(), propertyKey, newValue);
          yield replaceNode(
              workflow,
              new Node(
                  node.id(),
                  node.type(),
                  node.label(),
                  node.inputPorts(),
                  node.outputPorts(),
                  metadata));
        }
        case EDGE -> {
          Edge edge = requireEdge(workflow, targetId, "UpdateProperty");
          Metadata metadata = updateMetadata(edge.metadata(), propertyKey, newValue);
          List<Edge> edges = new ArrayList<>(workflow.edges().size());
          for (Edge existingEdge : workflow.edges()) {
            if (existingEdge.id().equals(edge.id())) {
              edges.add(
                  new Edge(
                      edge.id(),
                      edge.sourceNodeId(),
                      edge.sourcePortId(),
                      edge.targetNodeId(),
                      edge.targetPortId(),
                      metadata));
            } else {
              edges.add(existingEdge);
            }
          }
          yield new Workflow(
              workflow.id(), workflow.name(), workflow.nodes(), edges, workflow.metadata());
        }
      };
    }
  }

  /** Target object type for {@link UpdateProperty}. */
  enum PropertyTarget {
    WORKFLOW,
    NODE,
    EDGE
  }

  /**
   * Operation that assigns nodes to a semantic group.
   *
   * @param operationId unique operation id
   * @param timestamp operation timestamp
   * @param author operation author
   * @param groupId target group id
   * @param groupLabel human-readable group label
   * @param nodeIds grouped node ids
   * @param previousGroupIds previous group id per node
   */
  record GroupNodes(
      String operationId,
      Instant timestamp,
      String author,
      String groupId,
      String groupLabel,
      List<String> nodeIds,
      Map<String, String> previousGroupIds)
      implements WorkflowOperation {

    private static final String GROUP_KEY = "group.id";
    private static final String GROUP_LABEL_PREFIX = "group.";

    public GroupNodes {
      requireOperationMetadata(operationId, timestamp, author, "GroupNodes");
      StableIds.requireStable(groupId, "GroupNodes.groupId");
      if (groupLabel == null || groupLabel.isBlank()) {
        throw new IllegalArgumentException("GroupNodes.groupLabel must not be blank");
      }
      Objects.requireNonNull(nodeIds, "nodeIds");
      if (nodeIds.isEmpty()) {
        throw new IllegalArgumentException("GroupNodes.nodeIds must not be empty");
      }
      nodeIds = List.copyOf(nodeIds);
      previousGroupIds = requiredPreviousGroups(nodeIds, previousGroupIds, "GroupNodes");
    }

    @Override
    public Map<String, String> previousGroupIds() {
      return Map.copyOf(previousGroupIds);
    }

    @Override
    public List<String> affectedObjectIds() {
      return nodeIds;
    }

    @Override
    public Map<String, String> payload() {
      return Map.of(
          "groupId",
          groupId,
          "groupLabel",
          groupLabel,
          "nodeCount",
          String.valueOf(nodeIds.size()));
    }

    @Override
    public Optional<WorkflowOperation> inverseOperation() {
      return Optional.of(
          new UngroupNodes(
              operationId + UNDO_SUFFIX,
              timestamp,
              author,
              groupId,
              groupLabel,
              nodeIds,
              previousGroupIds));
    }

    @Override
    public Workflow apply(Workflow workflow) {
      Objects.requireNonNull(workflow, WORKFLOW_FIELD);
      Set<String> nodeSet = new LinkedHashSet<>(nodeIds);
      List<Node> nodes = new ArrayList<>(workflow.nodes().size());
      for (Node node : workflow.nodes()) {
        if (nodeSet.contains(node.id())) {
          Metadata metadata = updateMetadata(node.metadata(), GROUP_KEY, groupId);
          nodes.add(
              new Node(
                  node.id(),
                  node.type(),
                  node.label(),
                  node.inputPorts(),
                  node.outputPorts(),
                  metadata));
        } else {
          nodes.add(node);
        }
      }
      String groupLabelKey = GROUP_LABEL_PREFIX + groupId + ".label";
      Metadata workflowMetadata = updateMetadata(workflow.metadata(), groupLabelKey, groupLabel);
      return new Workflow(
          workflow.id(), workflow.name(), nodes, workflow.edges(), workflowMetadata);
    }
  }

  /**
   * Operation that removes nodes from a semantic group.
   *
   * @param operationId unique operation id
   * @param timestamp operation timestamp
   * @param author operation author
   * @param groupId group id being removed
   * @param groupLabel group label to preserve for undo
   * @param nodeIds node ids to ungroup
   * @param previousGroupIds previous group id per node
   */
  record UngroupNodes(
      String operationId,
      Instant timestamp,
      String author,
      String groupId,
      String groupLabel,
      List<String> nodeIds,
      Map<String, String> previousGroupIds)
      implements WorkflowOperation {

    private static final String GROUP_KEY = "group.id";
    private static final String GROUP_LABEL_PREFIX = "group.";

    public UngroupNodes {
      requireOperationMetadata(operationId, timestamp, author, "UngroupNodes");
      StableIds.requireStable(groupId, "UngroupNodes.groupId");
      if (groupLabel == null || groupLabel.isBlank()) {
        throw new IllegalArgumentException("UngroupNodes.groupLabel must not be blank");
      }
      Objects.requireNonNull(nodeIds, "nodeIds");
      if (nodeIds.isEmpty()) {
        throw new IllegalArgumentException("UngroupNodes.nodeIds must not be empty");
      }
      nodeIds = List.copyOf(nodeIds);
      previousGroupIds = requiredPreviousGroups(nodeIds, previousGroupIds, "UngroupNodes");
    }

    @Override
    public Map<String, String> previousGroupIds() {
      return Map.copyOf(previousGroupIds);
    }

    @Override
    public List<String> affectedObjectIds() {
      return nodeIds;
    }

    @Override
    public Map<String, String> payload() {
      return Map.of(
          "groupId",
          groupId,
          "groupLabel",
          groupLabel,
          "nodeCount",
          String.valueOf(nodeIds.size()));
    }

    @Override
    public Optional<WorkflowOperation> inverseOperation() {
      return Optional.of(
          new GroupNodes(
              operationId + UNDO_SUFFIX,
              timestamp,
              author,
              groupId,
              groupLabel,
              nodeIds,
              previousGroupIds));
    }

    @Override
    public Workflow apply(Workflow workflow) {
      Objects.requireNonNull(workflow, WORKFLOW_FIELD);
      Set<String> nodeSet = new LinkedHashSet<>(nodeIds);
      List<Node> nodes = new ArrayList<>(workflow.nodes().size());
      for (Node node : workflow.nodes()) {
        if (nodeSet.contains(node.id())) {
          String previousGroupId = previousGroupIds.get(node.id());
          String restoredGroupId = NO_GROUP.equals(previousGroupId) ? null : previousGroupId;
          Metadata metadata = updateMetadata(node.metadata(), GROUP_KEY, restoredGroupId);
          nodes.add(
              new Node(
                  node.id(),
                  node.type(),
                  node.label(),
                  node.inputPorts(),
                  node.outputPorts(),
                  metadata));
        } else {
          nodes.add(node);
        }
      }
      String groupLabelKey = GROUP_LABEL_PREFIX + groupId + ".label";
      Metadata workflowMetadata = updateMetadata(workflow.metadata(), groupLabelKey, null);
      return new Workflow(
          workflow.id(), workflow.name(), nodes, workflow.edges(), workflowMetadata);
    }
  }

  /**
   * Internal restore operation used as inverse for {@link DeleteNode}.
   *
   * @param operationId unique operation id
   * @param timestamp operation timestamp
   * @param author operation author
   * @param restoredNode node snapshot to restore
   * @param restoredEdges edge snapshots to restore
   */
  record RestoreNode(
      String operationId,
      Instant timestamp,
      String author,
      Node restoredNode,
      List<Edge> restoredEdges)
      implements WorkflowOperation {

    public RestoreNode {
      requireOperationMetadata(operationId, timestamp, author, "RestoreNode");
      Objects.requireNonNull(restoredNode, "restoredNode");
      Objects.requireNonNull(restoredEdges, "restoredEdges");
      restoredEdges = List.copyOf(restoredEdges);
    }

    @Override
    public List<String> affectedObjectIds() {
      List<String> ids = new ArrayList<>();
      ids.add(restoredNode.id());
      for (Edge edge : restoredEdges) {
        ids.add(edge.id());
      }
      return List.copyOf(ids);
    }

    @Override
    public Map<String, String> payload() {
      return Map.of(
          NODE_ID, restoredNode.id(), "restoredEdgeCount", String.valueOf(restoredEdges.size()));
    }

    @Override
    public Optional<WorkflowOperation> inverseOperation() {
      List<String> affectedIds = new ArrayList<>();
      affectedIds.add(restoredNode.id());
      for (Edge edge : restoredEdges) {
        affectedIds.add(edge.id());
      }
      return Optional.of(
          new DeleteNode(
              operationId + UNDO_SUFFIX,
              timestamp,
              author,
              restoredNode,
              restoredEdges,
              affectedIds));
    }

    @Override
    public Workflow apply(Workflow workflow) {
      Objects.requireNonNull(workflow, WORKFLOW_FIELD);
      boolean nodeExists =
          workflow.nodes().stream().anyMatch(node -> node.id().equals(restoredNode.id()));
      if (nodeExists) {
        throw new IllegalArgumentException("node already exists: " + restoredNode.id());
      }
      List<Node> nodes = new ArrayList<>(workflow.nodes());
      nodes.add(restoredNode);

      Set<String> edgeIds = new LinkedHashSet<>();
      for (Edge edge : workflow.edges()) {
        edgeIds.add(edge.id());
      }
      List<Edge> edges = new ArrayList<>(workflow.edges());
      for (Edge edge : restoredEdges) {
        if (!edgeIds.contains(edge.id())) {
          edges.add(edge);
        }
      }
      return new Workflow(workflow.id(), workflow.name(), nodes, edges, workflow.metadata());
    }
  }
}
