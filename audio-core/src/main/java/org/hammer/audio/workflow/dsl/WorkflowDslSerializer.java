package org.hammer.audio.workflow.dsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Port;
import org.hammer.audio.workflow.Workflow;

/**
 * Deterministic text serializer for {@link Workflow} objects.
 *
 * <p>Produces byte-identical output for semantically equivalent workflows. Node, edge, port and
 * metadata entries are sorted by their stable identifiers so that Git diffs remain meaningful.
 * Layout, presence and viewport state are excluded.
 *
 * <p>Owned by the DSL layer. May be used by the persistence facade and application services, but
 * must not depend on UI, JGit or execution internals.
 *
 * @see WorkflowDslParser
 */
public final class WorkflowDslSerializer {

  private static final String INDENT = "  ";
  private static final String NL = "\n";

  /** Serializes {@code workflow} to a deterministic UTF-8 text string. */
  public String serialize(Workflow workflow) {
    StringBuilder sb = new StringBuilder();
    sb.append("workflow").append(NL);
    sb.append(INDENT).append("id: ").append(escapeValue(workflow.id())).append(NL);
    sb.append(INDENT).append("name: ").append(escapeValue(workflow.name())).append(NL);
    appendMetadata(sb, INDENT, workflow.metadata());
    sb.append(INDENT).append("nodes:").append(NL);
    List<Node> sortedNodes = sortedById(workflow.nodes(), Node::id);
    for (Node node : sortedNodes) {
      appendNode(sb, node);
    }
    sb.append(INDENT).append("edges:").append(NL);
    List<Edge> sortedEdges = sortedById(workflow.edges(), Edge::id);
    for (Edge edge : sortedEdges) {
      appendEdge(sb, edge);
    }
    return sb.toString();
  }

  private void appendNode(StringBuilder sb, Node node) {
    String i2 = INDENT + INDENT;
    String i3 = i2 + INDENT;
    sb.append(i2).append("- id: ").append(escapeValue(node.id())).append(NL);
    sb.append(i3).append("type: ").append(escapeValue(node.type())).append(NL);
    sb.append(i3).append("label: ").append(escapeValue(node.label())).append(NL);
    appendMetadata(sb, i3, node.metadata());
    if (!node.inputPorts().isEmpty()) {
      sb.append(i3).append("inputPorts:").append(NL);
      for (Port port : sortedById(node.inputPorts(), Port::id)) {
        appendPort(sb, i3 + INDENT, port);
      }
    }
    if (!node.outputPorts().isEmpty()) {
      sb.append(i3).append("outputPorts:").append(NL);
      for (Port port : sortedById(node.outputPorts(), Port::id)) {
        appendPort(sb, i3 + INDENT, port);
      }
    }
  }

  private void appendPort(StringBuilder sb, String indent, Port port) {
    String i2 = indent + INDENT;
    sb.append(indent).append("- id: ").append(escapeValue(port.id())).append(NL);
    sb.append(i2).append("name: ").append(escapeValue(port.name())).append(NL);
    sb.append(i2).append("direction: ").append(port.direction().name()).append(NL);
    sb.append(i2).append("dataType: ").append(escapeValue(port.dataType().id())).append(NL);
    sb.append(i2).append("required: ").append(port.required()).append(NL);
    sb.append(i2).append("multiplicity: ").append(port.multiplicity().name()).append(NL);
    appendMetadata(sb, i2, port.metadata());
  }

  private void appendEdge(StringBuilder sb, Edge edge) {
    String i2 = INDENT + INDENT;
    String i3 = i2 + INDENT;
    sb.append(i2).append("- id: ").append(escapeValue(edge.id())).append(NL);
    sb.append(i3).append("sourceNodeId: ").append(escapeValue(edge.sourceNodeId())).append(NL);
    sb.append(i3).append("sourcePortId: ").append(escapeValue(edge.sourcePortId())).append(NL);
    sb.append(i3).append("targetNodeId: ").append(escapeValue(edge.targetNodeId())).append(NL);
    sb.append(i3).append("targetPortId: ").append(escapeValue(edge.targetPortId())).append(NL);
    appendMetadata(sb, i3, edge.metadata());
  }

  private void appendMetadata(StringBuilder sb, String indent, Metadata metadata) {
    if (metadata == null || metadata.entries().isEmpty()) {
      return;
    }
    sb.append(indent).append("metadata:").append(NL);
    String i2 = indent + INDENT;
    Map<String, String> sorted = new TreeMap<>(metadata.entries());
    for (Map.Entry<String, String> entry : sorted.entrySet()) {
      sb.append(i2)
          .append(escapeValue(entry.getKey()))
          .append(": ")
          .append(escapeValue(entry.getValue()))
          .append(NL);
    }
  }

  /** Escapes a string value for safe inclusion in the DSL text. */
  static String escapeValue(String value) {
    if (value == null) {
      return "~";
    }
    if (needsQuoting(value)) {
      return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
    return value;
  }

  private static boolean needsQuoting(String value) {
    if (value.isEmpty()) {
      return true;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == ':' || c == '"' || c == '\\' || c == '\n' || c == '#' || c == '[' || c == ']') {
        return true;
      }
    }
    return false;
  }

  @FunctionalInterface
  private interface IdGetter<T> {
    String id(T item);
  }

  private <T> List<T> sortedById(List<T> items, IdGetter<T> getter) {
    List<T> sorted = new ArrayList<>(items);
    sorted.sort((a, b) -> getter.id(a).compareTo(getter.id(b)));
    return sorted;
  }
}
