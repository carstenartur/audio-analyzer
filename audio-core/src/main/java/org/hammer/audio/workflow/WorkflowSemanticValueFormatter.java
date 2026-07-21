package org.hammer.audio.workflow;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/** Canonical textual evidence for semantic workflow comparison and merge conflicts. */
public final class WorkflowSemanticValueFormatter {

  private WorkflowSemanticValueFormatter() {
    throw new UnsupportedOperationException("Utility class");
  }

  /** Returns a canonical value for one complete node snapshot. */
  public static String node(Node node) {
    return "node{id="
        + quote(node.id())
        + ",type="
        + quote(node.type())
        + ",label="
        + quote(node.label())
        + ",inputPorts="
        + ports(node.inputPorts())
        + ",outputPorts="
        + ports(node.outputPorts())
        + ",metadata="
        + metadata(node.metadata())
        + "}";
  }

  /** Returns a canonical value for one complete edge snapshot. */
  public static String edge(Edge edge) {
    return "edge{id="
        + quote(edge.id())
        + ",source="
        + endpoint(edge.sourceNodeId(), edge.sourcePortId())
        + ",target="
        + endpoint(edge.targetNodeId(), edge.targetPortId())
        + ",metadata="
        + metadata(edge.metadata())
        + "}";
  }

  /** Returns a canonical node snapshot together with all incident edges. */
  public static String neighborhood(Node node, List<Edge> connectedEdges) {
    if (node == null) {
      return null;
    }
    StringJoiner edges = new StringJoiner(",", "[", "]");
    connectedEdges.stream()
        .sorted(Comparator.comparing(Edge::id))
        .map(WorkflowSemanticValueFormatter::edge)
        .forEach(edges::add);
    return "neighborhood{node=" + node(node) + ",edges=" + edges + "}";
  }

  /** Returns canonical source and target endpoints for one edge. */
  public static String endpoints(Edge edge) {
    return endpoint(edge.sourceNodeId(), edge.sourcePortId())
        + "->"
        + endpoint(edge.targetNodeId(), edge.targetPortId());
  }

  /** Returns canonical declared-order port evidence. */
  public static String ports(List<Port> ports) {
    StringJoiner values = new StringJoiner(",", "[", "]");
    for (Port port : ports) {
      values.add(port(port));
    }
    return values.toString();
  }

  /** Returns canonical key-ordered metadata evidence. */
  public static String metadata(Metadata metadata) {
    return metadata(metadata.entries());
  }

  /** Returns canonical key-ordered metadata-map evidence. */
  public static String metadata(Map<String, String> entries) {
    StringJoiner values = new StringJoiner(",", "{", "}");
    for (Map.Entry<String, String> entry : new TreeMap<>(entries).entrySet()) {
      values.add(quote(entry.getKey()) + ":" + quote(entry.getValue()));
    }
    return values.toString();
  }

  private static String port(Port port) {
    return "port{id="
        + quote(port.id())
        + ",name="
        + quote(port.name())
        + ",direction="
        + port.direction().name()
        + ",dataType="
        + quote(port.dataType().id())
        + ",required="
        + port.required()
        + ",multiplicity="
        + port.multiplicity().name()
        + ",metadata="
        + metadata(port.metadata())
        + "}";
  }

  private static String endpoint(String nodeId, String portId) {
    return quote(nodeId) + ":" + quote(portId);
  }

  private static String quote(String value) {
    return "\"" + escape(value) + "\"";
  }

  private static String escape(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }
}
