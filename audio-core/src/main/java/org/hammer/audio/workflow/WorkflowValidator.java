package org.hammer.audio.workflow;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Structural workflow validation for the pure domain model. */
public final class WorkflowValidator {

  private static final String EDGE_PREFIX = "Edge ";

  public List<String> validate(Workflow workflow) {
    Set<String> nodeIds = new LinkedHashSet<>();
    Set<String> edgeIds = new LinkedHashSet<>();
    List<String> violations = new ArrayList<>();

    for (Node node : workflow.nodes()) {
      if (!nodeIds.add(node.id())) {
        violations.add("Duplicate node id: " + node.id());
      }
      validatePorts(node, node.inputPorts(), PortDirection.INPUT, "inputPorts", violations);
      validatePorts(node, node.outputPorts(), PortDirection.OUTPUT, "outputPorts", violations);
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

  private static void validatePorts(
      Node node,
      List<Port> ports,
      PortDirection expectedDirection,
      String collectionName,
      List<String> violations) {
    Set<String> portIds = new LinkedHashSet<>();
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
    }
  }

  private static void validateEdge(Edge edge, Workflow workflow, List<String> violations) {
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
  }

  private static Node findNode(Workflow workflow, String nodeId) {
    return workflow.nodes().stream()
        .filter(node -> node.id().equals(nodeId))
        .findFirst()
        .orElse(null);
  }
}
