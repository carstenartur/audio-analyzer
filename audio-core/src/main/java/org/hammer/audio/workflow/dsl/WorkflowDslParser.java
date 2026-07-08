package org.hammer.audio.workflow.dsl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hammer.audio.workflow.DataType;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Port;
import org.hammer.audio.workflow.PortDirection;
import org.hammer.audio.workflow.PortMultiplicity;
import org.hammer.audio.workflow.Workflow;

/**
 * Deterministic text parser for {@link Workflow} objects serialized by {@link
 * WorkflowDslSerializer}.
 *
 * <p>Rebuilds the full workflow domain model from the canonical DSL text. Instances are stateless
 * and may be safely reused across parse invocations.
 *
 * <p>Owned by the DSL layer. May be used by the persistence facade and application services, but
 * must not depend on UI, JGit or execution internals.
 *
 * @see WorkflowDslSerializer
 */
public final class WorkflowDslParser {

  private static final String KEY_METADATA = "metadata";
  private static final String KEY_NODES = "nodes";
  private static final String KEY_EDGES = "edges";

  /**
   * Parses a workflow from the canonical DSL text produced by {@link WorkflowDslSerializer}.
   *
   * @param text DSL text
   * @return reconstructed workflow
   * @throws WorkflowDslParseException if the text is malformed
   */
  public Workflow parse(String text) {
    List<String> lines = splitLines(text);
    ParserState state = new ParserState(lines);
    return parseWorkflow(state);
  }

  private Workflow parseWorkflow(ParserState state) {
    state.expectKeyword("workflow");
    String id = state.readField("id");
    String name = state.readField("name");
    Metadata meta = Metadata.empty();
    if (state.peekKey(KEY_METADATA)) {
      meta = parseMetadata(state);
    }
    List<Node> nodes = new ArrayList<>();
    if (state.peekKey(KEY_NODES)) {
      nodes = parseNodes(state);
    }
    List<Edge> edges = new ArrayList<>();
    if (state.peekKey(KEY_EDGES)) {
      edges = parseEdges(state);
    }
    return new Workflow(id, name, nodes, edges, meta);
  }

  private List<Node> parseNodes(ParserState state) {
    state.expectSectionHeader(KEY_NODES);
    List<Node> nodes = new ArrayList<>();
    while (state.peekListItem(2)) {
      nodes.add(parseNode(state));
    }
    return nodes;
  }

  private Node parseNode(ParserState state) {
    String id = state.readListItemField("id", 2);
    String type = state.readField("type", 3);
    String label = state.readField("label", 3);
    Metadata metadata = Metadata.empty();
    if (state.peekKey(KEY_METADATA, 3)) {
      metadata = parseMetadata(state, 3);
    }
    List<Port> inputPorts = new ArrayList<>();
    if (state.peekKey("inputPorts", 3)) {
      inputPorts = parsePorts(state, 3);
    }
    List<Port> outputPorts = new ArrayList<>();
    if (state.peekKey("outputPorts", 3)) {
      outputPorts = parsePorts(state, 3);
    }
    return new Node(id, type, label, inputPorts, outputPorts, metadata);
  }

  private List<Port> parsePorts(ParserState state, int parentIndentLevel) {
    state.expectSectionHeader(parentIndentLevel);
    List<Port> ports = new ArrayList<>();
    while (state.peekListItem(parentIndentLevel + 1)) {
      ports.add(parsePort(state, parentIndentLevel + 1));
    }
    return ports;
  }

  private Port parsePort(ParserState state, int level) {
    String id = state.readListItemField("id", level);
    String name = state.readField("name", level + 1);
    PortDirection direction = PortDirection.valueOf(state.readField("direction", level + 1));
    DataType dataType = new DataType(state.readField("dataType", level + 1));
    boolean required = Boolean.parseBoolean(state.readField("required", level + 1));
    PortMultiplicity multiplicity =
        PortMultiplicity.valueOf(state.readField("multiplicity", level + 1));
    Metadata metadata = Metadata.empty();
    if (state.peekKey(KEY_METADATA, level + 1)) {
      metadata = parseMetadata(state, level + 1);
    }
    return new Port(id, name, direction, dataType, required, multiplicity, metadata);
  }

  private List<Edge> parseEdges(ParserState state) {
    state.expectSectionHeader(KEY_EDGES);
    List<Edge> edges = new ArrayList<>();
    while (state.peekListItem(2)) {
      edges.add(parseEdge(state));
    }
    return edges;
  }

  private Edge parseEdge(ParserState state) {
    String id = state.readListItemField("id", 2);
    String sourceNodeId = state.readField("sourceNodeId", 3);
    String sourcePortId = state.readField("sourcePortId", 3);
    String targetNodeId = state.readField("targetNodeId", 3);
    String targetPortId = state.readField("targetPortId", 3);
    Metadata metadata = Metadata.empty();
    if (state.peekKey(KEY_METADATA, 3)) {
      metadata = parseMetadata(state, 3);
    }
    return new Edge(id, sourceNodeId, sourcePortId, targetNodeId, targetPortId, metadata);
  }

  private Metadata parseMetadata(ParserState state) {
    return parseMetadata(state, 1);
  }

  private Metadata parseMetadata(ParserState state, int parentIndentLevel) {
    state.expectSectionHeader(parentIndentLevel);
    Map<String, String> entries = new HashMap<>();
    while (state.peekScalarField(parentIndentLevel + 1)) {
      String[] kv = state.readScalarKV(parentIndentLevel + 1);
      entries.put(kv[0], kv[1]);
    }
    return new Metadata(entries);
  }

  private static List<String> splitLines(String text) {
    List<String> lines = new ArrayList<>();
    for (String line : text.split("\n", -1)) {
      if (!line.isBlank()) {
        lines.add(line);
      }
    }
    return lines;
  }

  // ---------------------------------------------------------------------------
  // Inner parser state
  // ---------------------------------------------------------------------------

  private static final class ParserState {
    private static final String EXPECTED_QUOTE = "Expected '";
    private static final String AT_LINE = "' at line ";
    private static final String BUT_GOT = " but got: ";

    private final List<String> lines;
    private int pos;

    ParserState(List<String> lines) {
      this.lines = lines;
      this.pos = 0;
    }

    private String currentLine() {
      if (pos >= lines.size()) {
        return null;
      }
      return lines.get(pos);
    }

    private int indentOf(String line) {
      int count = 0;
      for (char c : line.toCharArray()) {
        if (c == ' ') {
          count++;
        } else {
          break;
        }
      }
      return count / 2;
    }

    void expectKeyword(String keyword) {
      String line = currentLine();
      if (line == null || !line.trim().equals(keyword)) {
        throw new WorkflowDslParseException(
            EXPECTED_QUOTE + keyword + AT_LINE + pos + BUT_GOT + line);
      }
      pos++;
    }

    void expectSectionHeader(String key) {
      String line = currentLine();
      if (line == null || !line.trim().equals(key + ":")) {
        throw new WorkflowDslParseException(
            EXPECTED_QUOTE + key + ":" + AT_LINE + pos + BUT_GOT + line);
      }
      pos++;
    }

    void expectSectionHeader(int indentLevel) {
      String line = currentLine();
      if (line == null) {
        throw new WorkflowDslParseException("Expected section header at line " + pos);
      }
      String trimmed = line.trim();
      if (!trimmed.endsWith(":")) {
        throw new WorkflowDslParseException(
            "Expected section header '...:'  at line " + pos + BUT_GOT + line);
      }
      pos++;
    }

    String readField(String key) {
      return readField(key, 1);
    }

    String readField(String key, int level) {
      String line = currentLine();
      if (line == null) {
        throw new WorkflowDslParseException(EXPECTED_QUOTE + key + AT_LINE + pos);
      }
      int indent = indentOf(line);
      if (indent != level) {
        throw new WorkflowDslParseException(
            "Expected indent " + level + " for field " + EXPECTED_QUOTE + key + AT_LINE + pos);
      }
      String trimmed = line.trim();
      String prefix = key + ": ";
      if (!trimmed.startsWith(prefix)) {
        throw new WorkflowDslParseException(
            EXPECTED_QUOTE + prefix + AT_LINE + pos + BUT_GOT + trimmed);
      }
      pos++;
      return unescapeValue(trimmed.substring(prefix.length()));
    }

    String readListItemField(String key, int level) {
      String line = currentLine();
      if (line == null) {
        throw new WorkflowDslParseException("Expected list item at line " + pos);
      }
      int indent = indentOf(line);
      if (indent != level) {
        throw new WorkflowDslParseException(
            "Expected indent " + level + " for list item at line " + pos);
      }
      String trimmed = line.trim();
      String prefix = "- " + key + ": ";
      if (!trimmed.startsWith(prefix)) {
        throw new WorkflowDslParseException(
            EXPECTED_QUOTE + prefix + AT_LINE + pos + BUT_GOT + trimmed);
      }
      pos++;
      return unescapeValue(trimmed.substring(prefix.length()));
    }

    boolean peekKey(String key) {
      return peekKey(key, 1);
    }

    boolean peekKey(String key, int level) {
      String line = currentLine();
      if (line == null) {
        return false;
      }
      int indent = indentOf(line);
      if (indent != level) {
        return false;
      }
      String trimmed = line.trim();
      return trimmed.equals(key + ":") || trimmed.startsWith(key + ": ");
    }

    boolean peekListItem(int level) {
      String line = currentLine();
      if (line == null) {
        return false;
      }
      int indent = indentOf(line);
      return indent == level && line.trim().startsWith("- ");
    }

    boolean peekScalarField(int level) {
      String line = currentLine();
      if (line == null) {
        return false;
      }
      int indent = indentOf(line);
      if (indent != level) {
        return false;
      }
      String trimmed = line.trim();
      return !trimmed.startsWith("- ") && !trimmed.endsWith(":") && trimmed.contains(": ");
    }

    String[] readScalarKV(int level) {
      String line = currentLine();
      if (line == null) {
        throw new WorkflowDslParseException("Expected scalar key-value at line " + pos);
      }
      String trimmed = line.trim();
      int colonIdx = trimmed.indexOf(": ");
      if (colonIdx < 0) {
        throw new WorkflowDslParseException("Malformed key-value line at " + pos + ": " + trimmed);
      }
      pos++;
      String key = unescapeValue(trimmed.substring(0, colonIdx));
      String value = unescapeValue(trimmed.substring(colonIdx + 2));
      return new String[] {key, value};
    }

    private static String unescapeValue(String raw) {
      if ("~".equals(raw)) {
        return null;
      }
      if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
        return unescapeQuotedValue(raw.substring(1, raw.length() - 1));
      }
      return raw;
    }

    private static String unescapeQuotedValue(String raw) {
      StringBuilder value = new StringBuilder(raw.length());
      int index = 0;
      while (index < raw.length()) {
        char current = raw.charAt(index);
        if (current != '\\' || index + 1 >= raw.length()) {
          value.append(current);
          index++;
          continue;
        }
        char escaped = raw.charAt(index + 1);
        switch (escaped) {
          case 'n' -> value.append('\n');
          case '"' -> value.append('"');
          case '\\' -> value.append('\\');
          default -> value.append(raw, index, index + 2);
        }
        index += 2;
      }
      return value.toString();
    }
  }
}
