package org.hammer.audio.workflow.editor.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.catalog.ExperimentNodeCatalog;
import org.hammer.audio.workflow.editor.WorkflowEditorService;
import org.hammer.audio.workflow.editor.WorkflowOperationRejectedException;
import org.hammer.audio.workflow.editor.WorkflowProjection;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitInfo;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.WorkflowSnapshot;

/** Minimal HTTP adapter for the workflow editor MVP (ADR-007 / issue #210). */
public final class WorkflowEditorHttpAdapter {

  private static final int HTTP_OK = 200;
  private static final int HTTP_BAD_REQUEST = 400;
  private static final int HTTP_UNPROCESSABLE = 422;
  private static final int HTTP_METHOD_NOT_ALLOWED = 405;
  private static final String CONTENT_TYPE = "Content-Type";
  private static final String APPLICATION_JSON = "application/json; charset=utf-8";
  private static final String DEFAULT_BRANCH = "main";
  private static final int DEFAULT_HISTORY_LIMIT = 20;

  private final WorkflowEditorService editorService;
  private final ObjectMapper mapper;
  private HttpServer server;

  /**
   * Creates an adapter backed by the given workflow editor service.
   *
   * @param editorService the server-authoritative editor service
   */
  public WorkflowEditorHttpAdapter(WorkflowEditorService editorService) {
    this.editorService = Objects.requireNonNull(editorService, "editorService");
    this.mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
  }

  /**
   * Starts the HTTP server on the given port.
   *
   * @param port TCP port to bind
   * @throws IOException if the server cannot bind to the port
   */
  public void start(int port) throws IOException {
    server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/workflow/projection", this::handleProjection);
    server.createContext("/workflow/catalog", this::handleCatalog);
    server.createContext("/workflow/validation", this::handleValidation);
    server.createContext("/workflow/operations", this::handleOperations);
    server.createContext("/workflow/checkpoints", this::handleCheckpoints);
    server.createContext("/workflow/history", this::handleHistory);
    server.createContext("/workflow/load", this::handleLoad);
    server.createContext("/workflow/snapshot", this::handleSnapshot);
    server.start();
  }

  /**
   * Stops the HTTP server.
   *
   * @param delaySeconds seconds to wait before the server stops accepting new connections
   */
  public void stop(int delaySeconds) {
    if (server != null) {
      server.stop(delaySeconds);
    }
  }

  private void handleProjection(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      sendError(exchange, HTTP_METHOD_NOT_ALLOWED, "Method Not Allowed");
      return;
    }
    sendJson(exchange, HTTP_OK, mapper.writeValueAsBytes(editorService.currentProjection()));
  }

  private void handleCatalog(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      sendError(exchange, HTTP_METHOD_NOT_ALLOWED, "Method Not Allowed");
      return;
    }
    sendJson(exchange, HTTP_OK, mapper.writeValueAsBytes(catalogEntries()));
  }

  private void handleValidation(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      sendError(exchange, HTTP_METHOD_NOT_ALLOWED, "Method Not Allowed");
      return;
    }
    ViolationsResponse response = new ViolationsResponse(editorService.validate());
    sendJson(exchange, HTTP_OK, mapper.writeValueAsBytes(response));
  }

  private void handleOperations(HttpExchange exchange) throws IOException {
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      sendError(exchange, HTTP_METHOD_NOT_ALLOWED, "Method Not Allowed");
      return;
    }
    JsonNode json = readJson(exchange);
    if (json == null) {
      return;
    }
    try {
      WorkflowOperation operation = parseOperation(json);
      WorkflowProjection projection = editorService.applyOperation(operation);
      sendJson(exchange, HTTP_OK, mapper.writeValueAsBytes(projection));
    } catch (WorkflowOperationRejectedException ex) {
      sendJson(
          exchange,
          HTTP_UNPROCESSABLE,
          mapper.writeValueAsBytes(new ViolationsResponse(ex.violations())));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      sendError(exchange, HTTP_BAD_REQUEST, ex.getMessage());
    }
  }

  private void handleCheckpoints(HttpExchange exchange) throws IOException {
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      sendError(exchange, HTTP_METHOD_NOT_ALLOWED, "Method Not Allowed");
      return;
    }
    JsonNode json = readJson(exchange);
    if (json == null) {
      return;
    }
    String branch = textOrDefault(json, "branch", DEFAULT_BRANCH);
    String author = textOrDefault(json, "author", "web-editor");
    String message = textOrDefault(json, "message", "Workbench checkpoint");
    Instant timestamp =
        json.has("timestamp") ? Instant.parse(json.get("timestamp").asText()) : Instant.now();
    try {
      CommitMetadata metadata = new CommitMetadata(author, message, timestamp);
      CommitId commitId = editorService.checkpoint(branch, metadata);
      sendJson(exchange, HTTP_OK, mapper.writeValueAsBytes(new CheckpointResponse(commitId.value())));
    } catch (WorkflowOperationRejectedException ex) {
      sendJson(
          exchange,
          HTTP_UNPROCESSABLE,
          mapper.writeValueAsBytes(new ViolationsResponse(ex.violations())));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      sendError(exchange, HTTP_BAD_REQUEST, ex.getMessage());
    }
  }

  private void handleHistory(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      sendError(exchange, HTTP_METHOD_NOT_ALLOWED, "Method Not Allowed");
      return;
    }
    try {
      Map<String, String> query = parseQuery(exchange.getRequestURI());
      String branch = query.getOrDefault("branch", DEFAULT_BRANCH);
      int limit = parseLimit(query.get("limit"));
      List<HistoryEntry> entries =
          editorService.history(branch, limit).stream().map(HistoryEntry::from).toList();
      sendJson(exchange, HTTP_OK, mapper.writeValueAsBytes(entries));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      sendError(exchange, HTTP_BAD_REQUEST, ex.getMessage());
    }
  }

  private void handleLoad(HttpExchange exchange) throws IOException {
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      sendError(exchange, HTTP_METHOD_NOT_ALLOWED, "Method Not Allowed");
      return;
    }
    JsonNode json = readJson(exchange);
    if (json == null) {
      return;
    }
    try {
      WorkflowProjection projection;
      if (json.has("commitId") && !json.get("commitId").asText().isBlank()) {
        projection = editorService.loadGraph(new CommitId(json.get("commitId").asText()));
      } else {
        projection = editorService.loadGraph(textOrDefault(json, "branch", DEFAULT_BRANCH));
      }
      sendJson(exchange, HTTP_OK, mapper.writeValueAsBytes(projection));
    } catch (WorkflowOperationRejectedException ex) {
      sendJson(
          exchange,
          HTTP_UNPROCESSABLE,
          mapper.writeValueAsBytes(new ViolationsResponse(ex.violations())));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      sendError(exchange, HTTP_BAD_REQUEST, ex.getMessage());
    }
  }

  private void handleSnapshot(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      sendError(exchange, HTTP_METHOD_NOT_ALLOWED, "Method Not Allowed");
      return;
    }
    WorkflowSnapshot snapshot = editorService.executeSnapshot();
    sendJson(exchange, HTTP_OK, mapper.writeValueAsBytes(snapshot));
  }

  private JsonNode readJson(HttpExchange exchange) throws IOException {
    byte[] requestBody = exchange.getRequestBody().readAllBytes();
    try {
      return mapper.readTree(requestBody);
    } catch (IOException ex) {
      sendError(exchange, HTTP_BAD_REQUEST, "Invalid JSON: " + ex.getMessage());
      return null;
    }
  }

  private static WorkflowOperation parseOperation(JsonNode json) {
    String type = requiredText(json, "type");
    String operationId = requiredText(json, "operationId");
    String author = json.has("author") ? json.get("author").asText() : "web-editor";
    Instant timestamp =
        json.has("timestamp") ? Instant.parse(json.get("timestamp").asText()) : Instant.now();
    return switch (type) {
      case "CreateNode" -> {
        String nodeId = requiredText(json, "nodeId");
        String catalogType = requiredText(json, "catalogType");
        Node node = createCatalogNode(catalogType, nodeId);
        yield new WorkflowOperation.CreateNode(operationId, timestamp, author, node);
      }
      case "ConnectPorts" -> {
        JsonNode edgeJson = requireObject(json, "edge");
        Edge edge =
            new Edge(
                requiredText(edgeJson, "id"),
                requiredText(edgeJson, "sourceNodeId"),
                requiredText(edgeJson, "sourcePortId"),
                requiredText(edgeJson, "targetNodeId"),
                requiredText(edgeJson, "targetPortId"));
        yield new WorkflowOperation.ConnectPorts(operationId, timestamp, author, edge);
      }
      case "DisconnectPorts" -> {
        String edgeId = requiredText(json, "edgeId");
        JsonNode edgeJson = requireObject(json, "disconnectedEdge");
        Edge disconnectedEdge =
            new Edge(
                requiredText(edgeJson, "id"),
                requiredText(edgeJson, "sourceNodeId"),
                requiredText(edgeJson, "sourcePortId"),
                requiredText(edgeJson, "targetNodeId"),
                requiredText(edgeJson, "targetPortId"));
        yield new WorkflowOperation.DisconnectPorts(
            operationId, timestamp, author, edgeId, disconnectedEdge);
      }
      case "UpdateProperty" -> {
        String target = requiredText(json, "target");
        String targetId = requiredText(json, "targetId");
        String propertyKey = requiredText(json, "propertyKey");
        String newValue = json.has("newValue") ? json.get("newValue").asText() : null;
        String previousValue =
            json.has("previousValue") ? json.get("previousValue").asText() : null;
        WorkflowOperation.PropertyTarget propertyTarget = parsePropertyTarget(target);
        yield new WorkflowOperation.UpdateProperty(
            operationId,
            timestamp,
            author,
            propertyTarget,
            targetId,
            propertyKey,
            previousValue,
            newValue);
      }
      default -> throw new IllegalArgumentException("Unknown operation type: " + type);
    };
  }

  private static WorkflowOperation.PropertyTarget parsePropertyTarget(String target) {
    return Arrays.stream(WorkflowOperation.PropertyTarget.values())
        .filter(t -> t.name().equals(target))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown PropertyTarget: " + target));
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

  private static List<CatalogEntry> catalogEntries() {
    return List.of(
        CatalogEntry.from("recording-input", ExperimentNodeCatalog.recordingInput("catalog.recording")),
        CatalogEntry.from(
            "synthetic-signal-generator",
            ExperimentNodeCatalog.syntheticSignalGenerator("catalog.synthetic")),
        CatalogEntry.from("humbug-db-import", ExperimentNodeCatalog.humBugDbImport("catalog.humbug")),
        CatalogEntry.from("gain", ExperimentNodeCatalog.gain("catalog.gain")),
        CatalogEntry.from("bandpass-filter", ExperimentNodeCatalog.bandpassFilter("catalog.bandpass")),
        CatalogEntry.from("fft", ExperimentNodeCatalog.fft("catalog.fft")),
        CatalogEntry.from(
            "wingbeat-feature-extraction",
            ExperimentNodeCatalog.wingbeatFeatureExtraction("catalog.features")),
        CatalogEntry.from("classifier", ExperimentNodeCatalog.classifier("catalog.classifier")),
        CatalogEntry.from("localization", ExperimentNodeCatalog.localization("catalog.localization")),
        CatalogEntry.from("benchmark", ExperimentNodeCatalog.benchmark("catalog.benchmark")),
        CatalogEntry.from("report", ExperimentNodeCatalog.report("catalog.report")),
        CatalogEntry.from("evidence-export", ExperimentNodeCatalog.evidenceExport("catalog.evidence")));
  }

  private static JsonNode requireObject(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("Missing required object field: " + field);
    }
    return value;
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException("Missing required field: " + field);
    }
    return value.asText();
  }

  private static String textOrDefault(JsonNode node, String field, String fallback) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull() || value.asText().isBlank()) {
      return fallback;
    }
    return value.asText();
  }

  private static Map<String, String> parseQuery(URI uri) {
    Map<String, String> result = new LinkedHashMap<>();
    String query = uri.getRawQuery();
    if (query == null || query.isBlank()) {
      return result;
    }
    for (String pair : query.split("&")) {
      int separator = pair.indexOf('=');
      String key = separator >= 0 ? pair.substring(0, separator) : pair;
      String value = separator >= 0 ? pair.substring(separator + 1) : "";
      result.put(decode(key), decode(value));
    }
    return result;
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  private static int parseLimit(String rawLimit) {
    if (rawLimit == null || rawLimit.isBlank()) {
      return DEFAULT_HISTORY_LIMIT;
    }
    return Integer.parseInt(rawLimit);
  }

  private void sendJson(HttpExchange exchange, int status, byte[] body) throws IOException {
    exchange.getResponseHeaders().set(CONTENT_TYPE, APPLICATION_JSON);
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  private void sendError(HttpExchange exchange, int status, String message) throws IOException {
    byte[] body = message.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set(CONTENT_TYPE, "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  /** JSON response body for rejected operations or current validation status. */
  public record ViolationsResponse(List<String> violations) {

    public ViolationsResponse {
      violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
    }
  }

  /** JSON response body for a created checkpoint. */
  public record CheckpointResponse(String commitId) {}

  /** JSON response entry for checkpoint history. */
  public record HistoryEntry(
      String commitId, String workflowId, String author, String message, Instant timestamp) {

    static HistoryEntry from(CommitInfo info) {
      return new HistoryEntry(
          info.commitId().value(),
          info.workflowId(),
          info.metadata().author(),
          info.metadata().message(),
          info.metadata().timestamp());
    }
  }

  /** JSON response entry for node palette items. */
  public record CatalogEntry(
      String type,
      String label,
      List<WorkflowProjection.HandleProjection> inputHandles,
      List<WorkflowProjection.HandleProjection> outputHandles) {

    static CatalogEntry from(String type, Node node) {
      Workflow catalogWorkflow = new Workflow("catalog", "Catalog", List.of(node), List.of());
      WorkflowProjection.NodeProjection nodeProjection =
          WorkflowProjection.fromWorkflow(catalogWorkflow).nodes().get(0);
      return new CatalogEntry(
          type, node.label(), nodeProjection.inputHandles(), nodeProjection.outputHandles());
    }
  }
}
