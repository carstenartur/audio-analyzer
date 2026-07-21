package org.hammer.audio.workflow.merge;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Port;

/** Canonical text representation used only for semantic diff and conflict evidence. */
final class WorkflowSemanticValues {

  private WorkflowSemanticValues() {
    throw new UnsupportedOperationException("Utility class");
  }

  static String node(Node node) {
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

  static String edge(Edge edge) {
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

  static String endpoints(Edge edge) {
    return endpoint(edge.sourceNodeId(), edge.sourcePortId())
        + "->"
        + endpoint(edge.targetNodeId(), edge.targetPortId());
  }

  static String ports(List<Port> ports) {
    StringJoiner values = new StringJoiner(",", "[", "]");
    for (Port port : ports) {
      values.add(port(port));
    }
    return values.toString();
  }

  static String metadata(Metadata metadata) {
    return metadata(metadata.entries());
  }

  static String metadata(Map<String, String> entries) {
    StringJoiner values = new StringJoiner(",", "{", "}");
    for (Map.Entry<String, String> entry : new TreeMap<>(entries).entrySet()) {
      values.add(quote(entry.getKey()) + ":" + quote(entry.getValue()));
    }
    return values.toString();
  }

  static String nullable(String value) {
    return value == null ? null : value;
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
