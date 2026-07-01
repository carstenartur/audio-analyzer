package org.hammer.audio.workflow;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Structural workflow validation for the pure domain model. */
public final class WorkflowValidator {

  private static final String EDGE_PREFIX = "Edge ";
  private final TypeRegistry typeRegistry;

  public WorkflowValidator() {
    this(TypeRegistry.defaultRegistry());
  }

  public WorkflowValidator(TypeRegistry typeRegistry) {
    this.typeRegistry = Objects.requireNonNull(typeRegistry, "typeRegistry");
  }

  public List<String> validate(Workflow workflow) {
    Objects.requireNonNull(workflow, "workflow");
    Set<String> nodeIds = new LinkedHashSet<>();
    Set<String> edgeIds = new LinkedHashSet<>();
    List<String> violations = new ArrayList<>();

    for (Node node : workflow.nodes()) {
      if (!nodeIds.add(node.id())) {
        violations.add("Duplicate node id: " + node.id());
      }
      Set<String> portIds = new LinkedHashSet<>();
      validatePorts(
          node, node.inputPorts(), PortDirection.INPUT, "inputPorts", portIds, violations);
      validatePorts(
          node, node.outputPorts(), PortDirection.OUTPUT, "outputPorts", portIds, violations);
    }

    for (Edge edge : workflow.edges()) {
      if (!edgeIds.add(edge.id())) {
        violations.add("Duplicate edge id: " + edge.id());
      }
      validateEdge(edge, workflow, violations);
    }

    return List.copyOf(violations);
  }

  public boolean isValid(Workflow workflow) {
    return validate(workflow).isEmpty();
  }

  private void validatePorts(
      Node node,
      List<Port> ports,
      PortDirection expectedDirection,
      String collectionName,
      Set<String> portIds,
      List<String> violations) {
    for (Port port : ports) {
      if (port.direction() != expectedDirection) {
        violations.add(
            "Node "
                + node.id()
                + " has "
                + collectionName
                + " port "
                + port.id()
                + " with direction "
                + port.direction());
      }
      if (!portIds.add(port.id())) {
        violations.add("Node " + node.id() + " has duplicate port id: " + port.id());
      }
      if (!typeRegistry.isRegistered(port.dataType())) {
        violations.add(
            "Node "
                + node.id()
                + " has "
                + collectionName
                + " port "
                + port.id()
                + " with unknown data type "
                + port.dataType().id());
      }
    }
  }

  private void validateEdge(Edge edge, Workflow workflow, List<String> violations) {
    Node sourceNode = findNode(workflow, edge.sourceNodeId());
    if (sourceNode == null) {
      violations.add(
          EDGE_PREFIX + edge.id() + " references missing source node " + edge.sourceNodeId());
      return;
    }
    Node targetNode = findNode(workflow, edge.targetNodeId());
    if (targetNode == null) {
      violations.add(
          EDGE_PREFIX + edge.id() + " references missing target node " + edge.targetNodeId());
      return;
    }

    Port sourcePort =
        sourceNode.outputPorts().stream()
            .filter(port -> port.id().equals(edge.sourcePortId()))
            .findFirst()
            .orElse(null);
    if (sourcePort == null) {
      violations.add(
          EDGE_PREFIX
              + edge.id()
              + " references missing source port "
              + edge.sourceNodeId()
              + ":"
              + edge.sourcePortId());
    }

    Port targetPort =
        targetNode.inputPorts().stream()
            .filter(port -> port.id().equals(edge.targetPortId()))
            .findFirst()
            .orElse(null);
    if (targetPort == null) {
      violations.add(
          EDGE_PREFIX
              + edge.id()
              + " references missing target port "
              + edge.targetNodeId()
              + ":"
              + edge.targetPortId());
    }
    if (sourcePort != null
        && targetPort != null
        && !typeRegistry.areCompatible(sourcePort.dataType(), targetPort.dataType())) {
      violations.add(
          EDGE_PREFIX
              + edge.id()
              + " connects incompatible data types "
              + sourcePort.dataType().id()
              + " -> "
              + targetPort.dataType().id());
    }
  }

  private static Node findNode(Workflow workflow, String nodeId) {
    return workflow.nodes().stream()
        .filter(node -> node.id().equals(nodeId))
        .findFirst()
        .orElse(null);
  }
}
