package org.hammer.audio.workflow.editor.http;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.catalog.ExperimentNodeCatalog;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Shared Jackson-3 codec for API commands and durable operation rows. */
public final class WorkflowOperationJsonCodec {

  private static final String ALLOWED_PREFIX = "org.hammer.audio.workflow.WorkflowOperation$";
  private final ObjectMapper mapper;

  public WorkflowOperationJsonCodec(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  public String encode(WorkflowOperation operation) {
    Objects.requireNonNull(operation, "operation");
    ObjectNode root = mapper.createObjectNode();
    root.put("class", operation.getClass().getName());
    root.set("payload", mapper.valueToTree(operation));
    try {
      return mapper.writeValueAsString(root);
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot encode workflow operation", ex);
    }
  }

  public WorkflowOperation decode(String json) {
    try {
      JsonNode root = mapper.readTree(json);
      String className = requiredText(root, "class");
      if (!className.startsWith(ALLOWED_PREFIX)) {
        throw new IllegalArgumentException("Unsupported workflow operation class: " + className);
      }
      Class<?> type = Class.forName(className);
      Object decoded = mapper.treeToValue(root.get("payload"), type);
      if (!(decoded instanceof WorkflowOperation operation)) {
        throw new IllegalArgumentException("Decoded value is not a WorkflowOperation");
      }
      return operation;
    } catch (RuntimeException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalArgumentException("Cannot decode workflow operation", ex);
    }
  }

  /** Decodes the stable public command representation used by both editor endpoints. */
  public WorkflowOperation decodeApi(JsonNode json) {
    String type = requiredText(json, "type");
    String operationId = requiredText(json, "operationId");
    String author = json.has("author") ? json.get("author").asText() : "web-editor";
    Instant timestamp =
        json.has("timestamp") ? Instant.parse(json.get("timestamp").asText()) : Instant.now();
    return switch (type) {
      case "CreateNode" -> {
        String nodeId = requiredText(json, "nodeId");
        Node node = createCatalogNode(requiredText(json, "catalogType"), nodeId);
        yield new WorkflowOperation.CreateNode(operationId, timestamp, author, node);
      }
      case "ConnectPorts" -> {
        JsonNode edgeJson = requireObject(json, "edge");
        yield new WorkflowOperation.ConnectPorts(
            operationId,
            timestamp,
            author,
            new Edge(
                requiredText(edgeJson, "id"),
                requiredText(edgeJson, "sourceNodeId"),
                requiredText(edgeJson, "sourcePortId"),
                requiredText(edgeJson, "targetNodeId"),
                requiredText(edgeJson, "targetPortId")));
      }
      case "DisconnectPorts" -> {
        JsonNode edgeJson = requireObject(json, "disconnectedEdge");
        Edge edge =
            new Edge(
                requiredText(edgeJson, "id"),
                requiredText(edgeJson, "sourceNodeId"),
                requiredText(edgeJson, "sourcePortId"),
                requiredText(edgeJson, "targetNodeId"),
                requiredText(edgeJson, "targetPortId"));
        yield new WorkflowOperation.DisconnectPorts(
            operationId, timestamp, author, requiredText(json, "edgeId"), edge);
      }
      case "UpdateProperty" ->
          new WorkflowOperation.UpdateProperty(
              operationId,
              timestamp,
              author,
              parsePropertyTarget(requiredText(json, "target")),
              requiredText(json, "targetId"),
              requiredText(json, "propertyKey"),
              nullableText(json, "previousValue"),
              nullableText(json, "newValue"));
      default -> throw new IllegalArgumentException("Unknown operation type: " + type);
    };
  }

  private static WorkflowOperation.PropertyTarget parsePropertyTarget(String value) {
    return Arrays.stream(WorkflowOperation.PropertyTarget.values())
        .filter(candidate -> candidate.name().equals(value))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown PropertyTarget: " + value));
  }

  private static Node createCatalogNode(String catalogType, String nodeId) {
    return switch (catalogType) {
      case "recording-input" -> ExperimentNodeCatalog.recordingInput(nodeId);
      case "synthetic-signal-generator" -> ExperimentNodeCatalog.syntheticSignalGenerator(nodeId);
      case "humbug-db-import" -> ExperimentNodeCatalog.humBugDbImport(nodeId);
      case "gain" -> ExperimentNodeCatalog.gain(nodeId);
      case "bandpass-filter" -> ExperimentNodeCatalog.bandpassFilter(nodeId);
      case "fft" -> ExperimentNodeCatalog.fft(nodeId);
      case "wingbeat-feature-extraction" -> ExperimentNodeCatalog.wingbeatFeatureExtraction(nodeId);
      case "classifier" -> ExperimentNodeCatalog.classifier(nodeId);
      case "localization" -> ExperimentNodeCatalog.localization(nodeId);
      case "benchmark" -> ExperimentNodeCatalog.benchmark(nodeId);
      case "report" -> ExperimentNodeCatalog.report(nodeId);
      case "evidence-export" -> ExperimentNodeCatalog.evidenceExport(nodeId);
      default -> throw new IllegalArgumentException("Unknown catalog node type: " + catalogType);
    };
  }

  private static JsonNode requireObject(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("Missing required object field: " + field);
    }
    return value;
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null || value.isNull() || value.asText().isBlank()) {
      throw new IllegalArgumentException("Missing or blank field: " + field);
    }
    return value.asText();
  }

  private static String nullableText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }
}
