package org.hammer.audio.workflow.collaboration.store;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.workflow.DataType;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Port;
import org.hammer.audio.workflow.PortDirection;
import org.hammer.audio.workflow.PortMultiplicity;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.WorkflowOperation.PropertyTarget;

/**
 * Versioned deterministic codec for complete semantic workflow operations.
 *
 * <p>The existing persistence payload remains the compact idempotency fingerprint. This codec
 * stores the complete reconstructible operation body required for restart-safe undo and redo. The
 * format is a length-prefixed binary document transported as unpadded URL-safe Base64. It is not
 * Java object serialization and has no framework or persistence dependency.
 */
public final class WorkflowOperationBodyCodec {

  /** Current durable body format version. */
  public static final int CURRENT_VERSION = 1;

  private static final int MAGIC = 0x574F5042; // WOPB
  private static final int MAX_COLLECTION_SIZE = 1_000_000;
  private static final int MAX_STRING_BYTES = 16 * 1024 * 1024;

  private WorkflowOperationBodyCodec() {
    // Utility class.
  }

  /** Encodes a complete operation body for durable storage. */
  public static EncodedBody encode(WorkflowOperation operation) {
    WorkflowOperation requiredOperation = Objects.requireNonNull(operation, "operation");
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeInt(MAGIC);
        output.writeInt(CURRENT_VERSION);
        writeString(output, requiredOperation.getClass().getSimpleName());
        writeCommon(output, requiredOperation);
        writeSpecific(output, requiredOperation);
      }
      return new EncodedBody(
          CURRENT_VERSION,
          Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray()));
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to encode workflow operation body", exception);
    }
  }

  /**
   * Decodes a complete operation body previously produced by {@link #encode(WorkflowOperation)}.
   */
  public static WorkflowOperation decode(int version, String body) {
    if (version != CURRENT_VERSION) {
      throw new IllegalArgumentException("Unsupported workflow operation body version: " + version);
    }
    String requiredBody = requireNotBlank(body, "body");
    final byte[] bytes;
    try {
      bytes = Base64.getUrlDecoder().decode(requiredBody);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Workflow operation body is not valid Base64", exception);
    }

    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (input.readInt() != MAGIC) {
        throw new IllegalArgumentException("Workflow operation body has an invalid magic header");
      }
      int embeddedVersion = input.readInt();
      if (embeddedVersion != version) {
        throw new IllegalArgumentException(
            "Workflow operation body version mismatch: " + version + "/" + embeddedVersion);
      }
      String type = readString(input);
      CommonFields common = readCommon(input);
      WorkflowOperation operation = readSpecific(input, type, common);
      if (input.available() != 0) {
        throw new IllegalArgumentException("Workflow operation body contains trailing data");
      }
      return operation;
    } catch (EOFException exception) {
      throw new IllegalArgumentException("Workflow operation body is truncated", exception);
    } catch (IOException exception) {
      throw new IllegalArgumentException("Failed to decode workflow operation body", exception);
    }
  }

  /**
   * Recreates an operation with fresh command metadata while preserving its complete semantic body.
   *
   * <p>This is used for accepted undo/redo operations. It deliberately does not reuse the fixed
   * metadata returned by {@link WorkflowOperation#inverseOperation()}.
   */
  public static WorkflowOperation reidentify(
      WorkflowOperation operation, String operationId, Instant timestamp, String author) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(timestamp, "timestamp");
    return switch (operation) {
      case WorkflowOperation.CreateNode value ->
          new WorkflowOperation.CreateNode(operationId, timestamp, author, value.node());
      case WorkflowOperation.DeleteNode value ->
          new WorkflowOperation.DeleteNode(
              operationId,
              timestamp,
              author,
              value.deletedNode(),
              value.deletedEdges(),
              value.affectedObjectIds());
      case WorkflowOperation.MoveNode value ->
          new WorkflowOperation.MoveNode(
              operationId,
              timestamp,
              author,
              value.nodeId(),
              value.fromX(),
              value.fromY(),
              value.toX(),
              value.toY());
      case WorkflowOperation.RenameNode value ->
          new WorkflowOperation.RenameNode(
              operationId,
              timestamp,
              author,
              value.nodeId(),
              value.previousLabel(),
              value.newLabel());
      case WorkflowOperation.ConnectPorts value ->
          new WorkflowOperation.ConnectPorts(operationId, timestamp, author, value.edge());
      case WorkflowOperation.DisconnectPorts value ->
          new WorkflowOperation.DisconnectPorts(
              operationId, timestamp, author, value.edgeId(), value.disconnectedEdge());
      case WorkflowOperation.UpdateProperty value ->
          new WorkflowOperation.UpdateProperty(
              operationId,
              timestamp,
              author,
              value.target(),
              value.targetId(),
              value.propertyKey(),
              value.previousValue(),
              value.newValue());
      case WorkflowOperation.GroupNodes value ->
          new WorkflowOperation.GroupNodes(
              operationId,
              timestamp,
              author,
              value.groupId(),
              value.groupLabel(),
              value.nodeIds(),
              value.previousGroupIds());
      case WorkflowOperation.UngroupNodes value ->
          new WorkflowOperation.UngroupNodes(
              operationId,
              timestamp,
              author,
              value.groupId(),
              value.groupLabel(),
              value.nodeIds(),
              value.previousGroupIds());
      case WorkflowOperation.RestoreNode value ->
          new WorkflowOperation.RestoreNode(
              operationId, timestamp, author, value.restoredNode(), value.restoredEdges());
    };
  }

  private static void writeCommon(DataOutputStream output, WorkflowOperation operation)
      throws IOException {
    writeString(output, operation.operationId());
    writeString(output, operation.timestamp().toString());
    writeString(output, operation.author());
  }

  private static CommonFields readCommon(DataInputStream input) throws IOException {
    return new CommonFields(readString(input), Instant.parse(readString(input)), readString(input));
  }

  private static void writeSpecific(DataOutputStream output, WorkflowOperation operation)
      throws IOException {
    switch (operation) {
      case WorkflowOperation.CreateNode value -> writeNode(output, value.node());
      case WorkflowOperation.DeleteNode value -> {
        writeNode(output, value.deletedNode());
        writeEdges(output, value.deletedEdges());
      }
      case WorkflowOperation.MoveNode value -> {
        writeString(output, value.nodeId());
        output.writeDouble(value.fromX());
        output.writeDouble(value.fromY());
        output.writeDouble(value.toX());
        output.writeDouble(value.toY());
      }
      case WorkflowOperation.RenameNode value -> {
        writeString(output, value.nodeId());
        writeString(output, value.previousLabel());
        writeString(output, value.newLabel());
      }
      case WorkflowOperation.ConnectPorts value -> writeEdge(output, value.edge());
      case WorkflowOperation.DisconnectPorts value -> {
        writeString(output, value.edgeId());
        writeEdge(output, value.disconnectedEdge());
      }
      case WorkflowOperation.UpdateProperty value -> {
        writeString(output, value.target().name());
        writeString(output, value.targetId());
        writeString(output, value.propertyKey());
        writeNullableString(output, value.previousValue());
        writeNullableString(output, value.newValue());
      }
      case WorkflowOperation.GroupNodes value -> {
        writeString(output, value.groupId());
        writeString(output, value.groupLabel());
        writeStrings(output, value.nodeIds());
        writeMap(output, value.previousGroupIds());
      }
      case WorkflowOperation.UngroupNodes value -> {
        writeString(output, value.groupId());
        writeString(output, value.groupLabel());
        writeStrings(output, value.nodeIds());
        writeMap(output, value.previousGroupIds());
      }
      case WorkflowOperation.RestoreNode value -> {
        writeNode(output, value.restoredNode());
        writeEdges(output, value.restoredEdges());
      }
    }
  }

  private static WorkflowOperation readSpecific(
      DataInputStream input, String type, CommonFields common) throws IOException {
    return switch (type) {
      case "CreateNode" ->
          new WorkflowOperation.CreateNode(
              common.operationId(), common.timestamp(), common.author(), readNode(input));
      case "DeleteNode" -> {
        Node node = readNode(input);
        List<Edge> edges = readEdges(input);
        yield new WorkflowOperation.DeleteNode(
            common.operationId(), common.timestamp(), common.author(), node, edges, List.of());
      }
      case "MoveNode" ->
          new WorkflowOperation.MoveNode(
              common.operationId(),
              common.timestamp(),
              common.author(),
              readString(input),
              input.readDouble(),
              input.readDouble(),
              input.readDouble(),
              input.readDouble());
      case "RenameNode" ->
          new WorkflowOperation.RenameNode(
              common.operationId(),
              common.timestamp(),
              common.author(),
              readString(input),
              readString(input),
              readString(input));
      case "ConnectPorts" ->
          new WorkflowOperation.ConnectPorts(
              common.operationId(), common.timestamp(), common.author(), readEdge(input));
      case "DisconnectPorts" -> {
        String edgeId = readString(input);
        yield new WorkflowOperation.DisconnectPorts(
            common.operationId(), common.timestamp(), common.author(), edgeId, readEdge(input));
      }
      case "UpdateProperty" ->
          new WorkflowOperation.UpdateProperty(
              common.operationId(),
              common.timestamp(),
              common.author(),
              PropertyTarget.valueOf(readString(input)),
              readString(input),
              readString(input),
              readNullableString(input),
              readNullableString(input));
      case "GroupNodes" ->
          new WorkflowOperation.GroupNodes(
              common.operationId(),
              common.timestamp(),
              common.author(),
              readString(input),
              readString(input),
              readStrings(input),
              readMap(input));
      case "UngroupNodes" ->
          new WorkflowOperation.UngroupNodes(
              common.operationId(),
              common.timestamp(),
              common.author(),
              readString(input),
              readString(input),
              readStrings(input),
              readMap(input));
      case "RestoreNode" ->
          new WorkflowOperation.RestoreNode(
              common.operationId(),
              common.timestamp(),
              common.author(),
              readNode(input),
              readEdges(input));
      default ->
          throw new IllegalArgumentException("Unknown workflow operation body type: " + type);
    };
  }

  private static void writeNode(DataOutputStream output, Node node) throws IOException {
    writeString(output, node.id());
    writeString(output, node.type());
    writeString(output, node.label());
    writePorts(output, node.inputPorts());
    writePorts(output, node.outputPorts());
    writeMetadata(output, node.metadata());
  }

  private static Node readNode(DataInputStream input) throws IOException {
    return new Node(
        readString(input),
        readString(input),
        readString(input),
        readPorts(input),
        readPorts(input),
        readMetadata(input));
  }

  private static void writePorts(DataOutputStream output, List<Port> ports) throws IOException {
    writeSize(output, ports.size());
    for (Port port : ports) {
      writeString(output, port.id());
      writeString(output, port.name());
      writeString(output, port.direction().name());
      writeString(output, port.dataType().id());
      output.writeBoolean(port.required());
      writeString(output, port.multiplicity().name());
      writeMetadata(output, port.metadata());
    }
  }

  private static List<Port> readPorts(DataInputStream input) throws IOException {
    int size = readSize(input);
    List<Port> ports = new ArrayList<>(size);
    for (int index = 0; index < size; index++) {
      ports.add(
          new Port(
              readString(input),
              readString(input),
              PortDirection.valueOf(readString(input)),
              new DataType(readString(input)),
              input.readBoolean(),
              PortMultiplicity.valueOf(readString(input)),
              readMetadata(input)));
    }
    return List.copyOf(ports);
  }

  private static void writeEdges(DataOutputStream output, List<Edge> edges) throws IOException {
    writeSize(output, edges.size());
    for (Edge edge : edges) {
      writeEdge(output, edge);
    }
  }

  private static List<Edge> readEdges(DataInputStream input) throws IOException {
    int size = readSize(input);
    List<Edge> edges = new ArrayList<>(size);
    for (int index = 0; index < size; index++) {
      edges.add(readEdge(input));
    }
    return List.copyOf(edges);
  }

  private static void writeEdge(DataOutputStream output, Edge edge) throws IOException {
    writeString(output, edge.id());
    writeString(output, edge.sourceNodeId());
    writeString(output, edge.sourcePortId());
    writeString(output, edge.targetNodeId());
    writeString(output, edge.targetPortId());
    writeMetadata(output, edge.metadata());
  }

  private static Edge readEdge(DataInputStream input) throws IOException {
    return new Edge(
        readString(input),
        readString(input),
        readString(input),
        readString(input),
        readString(input),
        readMetadata(input));
  }

  private static void writeMetadata(DataOutputStream output, Metadata metadata) throws IOException {
    writeMap(output, metadata.entries());
  }

  private static Metadata readMetadata(DataInputStream input) throws IOException {
    Map<String, String> entries = readMap(input);
    return entries.isEmpty() ? Metadata.empty() : new Metadata(entries);
  }

  private static void writeStrings(DataOutputStream output, List<String> values)
      throws IOException {
    writeSize(output, values.size());
    for (String value : values) {
      writeString(output, value);
    }
  }

  private static List<String> readStrings(DataInputStream input) throws IOException {
    int size = readSize(input);
    List<String> values = new ArrayList<>(size);
    for (int index = 0; index < size; index++) {
      values.add(readString(input));
    }
    return List.copyOf(values);
  }

  private static void writeMap(DataOutputStream output, Map<String, String> values)
      throws IOException {
    writeSize(output, values.size());
    for (Map.Entry<String, String> entry :
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
      writeString(output, entry.getKey());
      writeString(output, entry.getValue());
    }
  }

  private static Map<String, String> readMap(DataInputStream input) throws IOException {
    int size = readSize(input);
    Map<String, String> values = new LinkedHashMap<>();
    for (int index = 0; index < size; index++) {
      String key = readString(input);
      String previous = values.put(key, readString(input));
      if (previous != null) {
        throw new IllegalArgumentException(
            "Workflow operation body contains duplicate map key: " + key);
      }
    }
    return Map.copyOf(values);
  }

  private static void writeNullableString(DataOutputStream output, String value)
      throws IOException {
    output.writeBoolean(value != null);
    if (value != null) {
      writeString(output, value);
    }
  }

  private static String readNullableString(DataInputStream input) throws IOException {
    return input.readBoolean() ? readString(input) : null;
  }

  private static void writeString(DataOutputStream output, String value) throws IOException {
    byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_STRING_BYTES) {
      throw new IllegalArgumentException("Workflow operation body string is too large");
    }
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static String readString(DataInputStream input) throws IOException {
    int length = input.readInt();
    if (length < 0 || length > MAX_STRING_BYTES) {
      throw new IllegalArgumentException(
          "Invalid workflow operation body string length: " + length);
    }
    byte[] bytes = new byte[length];
    input.readFully(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static void writeSize(DataOutputStream output, int size) throws IOException {
    if (size < 0 || size > MAX_COLLECTION_SIZE) {
      throw new IllegalArgumentException(
          "Invalid workflow operation body collection size: " + size);
    }
    output.writeInt(size);
  }

  private static int readSize(DataInputStream input) throws IOException {
    int size = input.readInt();
    if (size < 0 || size > MAX_COLLECTION_SIZE) {
      throw new IllegalArgumentException(
          "Invalid workflow operation body collection size: " + size);
    }
    return size;
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  /** Versioned durable operation body. */
  public record EncodedBody(int version, String body) {
    public EncodedBody {
      if (version <= 0) {
        throw new IllegalArgumentException("version must be > 0");
      }
      body = requireNotBlank(body, "body");
    }
  }

  private record CommonFields(String operationId, Instant timestamp, String author) {}
}
