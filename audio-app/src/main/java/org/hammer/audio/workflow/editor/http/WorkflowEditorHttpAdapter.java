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
import java.nio.file.Files;
import java.nio.file.Path;
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
  private static final String TEXT_PLAIN_UTF_8 = "text/plain; charset=utf-8";
  private static final String DEFAULT_BRANCH = "main";
  private static final int DEFAULT_HISTORY_LIMIT = 20;
  private static final String HTTP_GET = "GET";
  private static final String HTTP_POST = "POST";
  private static final String MSG_METHOD_NOT_ALLOWED = "Method Not Allowed";
  private static final String JSON_FIELD_COMMIT_ID = "commitId";
  private static final String JSON_FIELD_TIMESTAMP = "timestamp";

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
    HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);
    registerContexts(httpServer);
    httpServer.start();
  }

  /**
   * Starts the HTTP server on the given port and registers a static-file context.
   *
   * <p>Static files are served from the given {@code staticDir}. Workflow API paths take precedence
   * over the root path via JDK HttpServer longest-prefix routing.
   *
   * @param port TCP port to bind
   * @param staticDir directory from which static files are served at {@code /}
   * @throws IOException if the server cannot bind to the port
   */
  public void start(int port, Path staticDir) throws IOException {
    Objects.requireNonNull(staticDir, "staticDir");
    HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);
    httpServer.createContext("/", new StaticFileHandler(staticDir));
    registerContexts(httpServer);
    httpServer.start();
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
    if (!HTTP_GET.equalsIgnoreCase(exchange.getRequestMethod())) {
      sendError(exchange, HTTP_METHOD_NOT_ALLOWED, MSG_METHOD_NOT_ALLOWED);
      return;
    }
    sendJson(exchange, HTTP_OK, mapper.writeValueAsBytes(editorService.currentProjection()));
  }

  private void handleCatalog(HttpExchange exchange) throws IOException {
    if (!HTTP_GET.equalsIgnoreCase(exchange.getRequestMethod())) {
      sendError(exchange, HTTP_METHOD_NOT_ALLOWED, MSG_METHOD_NOT_ALLOWED);
      return;
    }
    sendJson(exchange, HTTP_OK, mapper.writeValueAsBytes(catalogEntries()));
  }

  private void handleValidation(HttpExchange exchange) throws IOException {
    if (!HTTP_GET.equalsIgnoreCase(exchange.getRequestMethod())) {
      sendError(exchange, HTTP_METHOD_NOT_ALLOWED, MSG_METHOD_NOT_ALLOWED);
      return;
    }
    ViolationsResponse response = new ViolationsResponse(editorService.validate());
    sendJson(exchange, HTTP_OK, mapper.writeValueAsBytes(response));
  }

  private void handleOperations(HttpExchange exchange) throws IOException {
    if (!HTTP_POST.equalsIgnoreCase(exchange.getRequestMethod())) {
      sendError(exchange, HTTP_METHOD_NOT_ALLOWED, MSG_METHOD_NOT_ALLOWED);
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
    if (!HTTP_POST.equalsIgnoreCase(exchange.getRequestMethod())) {
      sendError(exchange, HTTP_METHOD_NOT_ALLOWED, MSG_METHOD_NOT_ALLOWED);
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
        json.has(JSON_FIELD_TIMESTAMP)
            ? Instant.parse(json.get(JSON_FIELD_TIMESTAMP).asText())
            : Instant.now();
    try {
      CommitMetadata metadata = new CommitMetadata(author, message, timestamp);
      CommitId commitId = editorService.checkpoint(branch, metadata);
      sendJson(
          exchange, HTTP_OK, mapper.writeValueAsBytes(new CheckpointResponse(commitId.value())));
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
    if (!HTTP_GET.equalsIgnoreCase(exchange.getRequestMethod())) {
      sendError(exchange, HTTP_METHOD_NOT_ALLOWED, MSG_METHOD_NOT_ALLOWED);
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
    if (!HTTP_POST.equalsIgnoreCase(exchange.getRequestMethod())) {
      sendError(exchange, HTTP_METHOD_NOT_ALLOWED, MSG_METHOD_NOT_ALLOWED);
      return;
    }
    JsonNode json = readJson(exchange);
    if (json == null) {
      return;
    }
    try {
      WorkflowProjection projection;
      if (json.has(JSON_FIELD_COMMIT_ID) && !json.get(JSON_FIELD_COMMIT_ID).asText().isBlank()) {
        projection = editorService.loadGraph(new CommitId(json.get(JSON_FIELD_COMMIT_ID).asText()));
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
    if (!HTTP_GET.equalsIgnoreCase(exchange.getRequestMethod())) {
      sendError(exchange, HTTP_METHOD_NOT_ALLOWED, MSG_METHOD_NOT_ALLOWED);
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
        json.has(JSON_FIELD_TIMESTAMP)
            ? Instant.parse(json.get(JSON_FIELD_TIMESTAMP).asText())
            : Instant.now();
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
        CatalogEntry.from(
            "recording-input", ExperimentNodeCatalog.recordingInput("catalog.recording")),
        CatalogEntry.from(
            "synthetic-signal-generator",
            ExperimentNodeCatalog.syntheticSignalGenerator("catalog.synthetic")),
        CatalogEntry.from(
            "humbug-db-import", ExperimentNodeCatalog.humBugDbImport("catalog.humbug")),
        CatalogEntry.from("gain", ExperimentNodeCatalog.gain("catalog.gain")),
        CatalogEntry.from(
            "bandpass-filter", ExperimentNodeCatalog.bandpassFilter("catalog.bandpass")),
        CatalogEntry.from("fft", ExperimentNodeCatalog.fft("catalog.fft")),
        CatalogEntry.from(
            "wingbeat-feature-extraction",
            ExperimentNodeCatalog.wingbeatFeatureExtraction("catalog.features")),
        CatalogEntry.from("classifier", ExperimentNodeCatalog.classifier("catalog.classifier")),
        CatalogEntry.from(
            "localization", ExperimentNodeCatalog.localization("catalog.localization")),
        CatalogEntry.from("benchmark", ExperimentNodeCatalog.benchmark("catalog.benchmark")),
        CatalogEntry.from("report", ExperimentNodeCatalog.report("catalog.report")),
        CatalogEntry.from(
            "evidence-export", ExperimentNodeCatalog.evidenceExport("catalog.evidence")));
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
    exchange.getResponseHeaders().set(CONTENT_TYPE, TEXT_PLAIN_UTF_8);
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  /**
   * JSON response body for rejected operations or current validation status.
   *
   * @param violations list of structural violation messages
   */
  public record ViolationsResponse(List<String> violations) {

    public ViolationsResponse {
      violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
    }
  }

  /**
   * JSON response body for a created checkpoint.
   *
   * @param commitId stable identifier of the created commit
   */
  public record CheckpointResponse(String commitId) {
    public CheckpointResponse {
      Objects.requireNonNull(
          commitId, () -> "CheckpointResponse parameter commitId must not be null");
    }
  }

  /**
   * JSON response entry for checkpoint history.
   *
   * @param commitId stable identifier of the commit
   * @param workflowId domain identifier of the workflow
   * @param author author of the commit
   * @param message human-readable commit message
   * @param timestamp instant at which the commit was created
   */
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

  /**
   * JSON response entry for node palette items.
   *
   * @param type node type identifier
   * @param label human-readable node label
   * @param inputHandles typed input port handles
   * @param outputHandles typed output port handles
   */
  public record CatalogEntry(
      String type,
      String label,
      List<WorkflowProjection.HandleProjection> inputHandles,
      List<WorkflowProjection.HandleProjection> outputHandles) {

    public CatalogEntry {
      inputHandles = List.copyOf(Objects.requireNonNull(inputHandles, "inputHandles"));
      outputHandles = List.copyOf(Objects.requireNonNull(outputHandles, "outputHandles"));
    }

    static CatalogEntry from(String type, Node node) {
      Workflow catalogWorkflow = new Workflow("catalog", "Catalog", List.of(node), List.of());
      WorkflowProjection.NodeProjection nodeProjection =
          WorkflowProjection.fromWorkflow(catalogWorkflow).nodes().get(0);
      return new CatalogEntry(
          type, node.label(), nodeProjection.inputHandles(), nodeProjection.outputHandles());
    }
  }

  /**
   * Registers all workflow API contexts onto the given HTTP server.
   *
   * <p>After this call the server has contexts for {@code /workflow/projection}, {@code
   * /workflow/catalog}, {@code /workflow/validation}, {@code /workflow/operations}, {@code
   * /workflow/checkpoints}, {@code /workflow/history}, {@code /workflow/load} and {@code
   * /workflow/snapshot}.
   *
   * @param httpServer the HTTP server to attach contexts to
   */
  void registerContexts(HttpServer httpServer) {
    server = httpServer;
    httpServer.createContext("/workflow/projection", this::handleProjection);
    httpServer.createContext("/workflow/catalog", this::handleCatalog);
    httpServer.createContext("/workflow/validation", this::handleValidation);
    httpServer.createContext("/workflow/operations", this::handleOperations);
    httpServer.createContext("/workflow/checkpoints", this::handleCheckpoints);
    httpServer.createContext("/workflow/history", this::handleHistory);
    httpServer.createContext("/workflow/load", this::handleLoad);
    httpServer.createContext("/workflow/snapshot", this::handleSnapshot);
  }

  /** Serves static files from a filesystem directory. */
  private static final class StaticFileHandler implements com.sun.net.httpserver.HttpHandler {

    private final Path root;

    StaticFileHandler(Path root) {
      this.root = root.normalize();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!HTTP_GET.equalsIgnoreCase(exchange.getRequestMethod())) {
        exchange.getResponseHeaders().set("Allow", HTTP_GET);
        sendStatic(
            exchange,
            HTTP_METHOD_NOT_ALLOWED,
            TEXT_PLAIN_UTF_8,
            MSG_METHOD_NOT_ALLOWED.getBytes(StandardCharsets.UTF_8));
        return;
      }
      String uriPath = exchange.getRequestURI().getPath();
      if (uriPath == null || "/".equals(uriPath) || uriPath.isEmpty()) {
        uriPath = "/index.html";
      }
      String relative = uriPath.startsWith("/") ? uriPath.substring(1) : uriPath;
      Path target = root.resolve(relative).normalize();
      if (!target.startsWith(root)) {
        sendStatic(exchange, 403, TEXT_PLAIN_UTF_8, "Forbidden".getBytes(StandardCharsets.UTF_8));
        return;
      }
      if (!Files.exists(target) || Files.isDirectory(target)) {
        sendStatic(exchange, 404, TEXT_PLAIN_UTF_8, "Not Found".getBytes(StandardCharsets.UTF_8));
        return;
      }
      byte[] content = Files.readAllBytes(target);
      sendStatic(exchange, HTTP_OK, guessContentType(target), content);
    }

    private static void sendStatic(
        HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
      exchange.getResponseHeaders().set(CONTENT_TYPE, contentType);
      exchange.sendResponseHeaders(status, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    }

    private static String guessContentType(Path path) {
      Path fileName = path.getFileName();
      if (fileName == null) {
        return "application/octet-stream";
      }
      String name = fileName.toString().toLowerCase(java.util.Locale.ROOT);
      if (name.endsWith(".html")) return "text/html; charset=utf-8";
      if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
      if (name.endsWith(".css")) return "text/css; charset=utf-8";
      if (name.endsWith(".json")) return "application/json; charset=utf-8";
      if (name.endsWith(".png")) return "image/png";
      if (name.endsWith(".svg")) return "image/svg+xml";
      return "application/octet-stream";
    }
  }
}
