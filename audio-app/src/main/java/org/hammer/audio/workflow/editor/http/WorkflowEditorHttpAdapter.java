package org.hammer.audio.workflow.editor.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.editor.WorkflowEditorService;
import org.hammer.audio.workflow.editor.WorkflowOperationRejectedException;
import org.hammer.audio.workflow.editor.WorkflowProjection;

/**
 * Minimal HTTP adapter for the workflow editor spike (ADR-007).
 *
 * <p>Exposes two endpoints:
 *
 * <ul>
 *   <li>{@code GET /workflow/projection} — returns the current {@link WorkflowProjection} as JSON.
 *   <li>{@code POST /workflow/operations} — accepts a {@link WorkflowOperation} encoded as JSON,
 *       applies it via {@link WorkflowEditorService}, and returns the updated {@link
 *       WorkflowProjection}, or HTTP 422 with a violations list if the operation is rejected.
 * </ul>
 *
 * <p>This adapter uses the JDK built-in {@code com.sun.net.httpserver.HttpServer}. No external web
 * framework dependency is required. It is intended for local spike development only and must not be
 * deployed as a production server.
 *
 * <p><b>Dependency rules</b>: this class must not import Swing, JGit, React, or Yjs types.
 */
public final class WorkflowEditorHttpAdapter {

  private static final int HTTP_OK = 200;
  private static final int HTTP_BAD_REQUEST = 400;
  private static final int HTTP_UNPROCESSABLE = 422;
  private static final int HTTP_METHOD_NOT_ALLOWED = 405;
  private static final String CONTENT_TYPE = "Content-Type";
  private static final String APPLICATION_JSON = "application/json; charset=utf-8";

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
    server.createContext("/workflow/operations", this::handleOperations);
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
    WorkflowProjection projection = editorService.currentProjection();
    sendJson(exchange, HTTP_OK, mapper.writeValueAsBytes(projection));
  }

  private void handleOperations(HttpExchange exchange) throws IOException {
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      sendError(exchange, HTTP_METHOD_NOT_ALLOWED, "Method Not Allowed");
      return;
    }
    byte[] requestBody = exchange.getRequestBody().readAllBytes();
    JsonNode json;
    try {
      json = mapper.readTree(requestBody);
    } catch (IOException ex) {
      sendError(exchange, HTTP_BAD_REQUEST, "Invalid JSON: " + ex.getMessage());
      return;
    }
    WorkflowOperation operation;
    try {
      operation = parseOperation(json);
    } catch (IllegalArgumentException ex) {
      sendError(exchange, HTTP_BAD_REQUEST, "Unrecognised operation: " + ex.getMessage());
      return;
    }
    try {
      WorkflowProjection projection = editorService.applyOperation(operation);
      sendJson(exchange, HTTP_OK, mapper.writeValueAsBytes(projection));
    } catch (WorkflowOperationRejectedException ex) {
      byte[] body = mapper.writeValueAsBytes(new ViolationsResponse(ex.violations()));
      sendJson(exchange, HTTP_UNPROCESSABLE, body);
    }
  }

  private static WorkflowOperation parseOperation(JsonNode json) {
    String type = requiredText(json, "type");
    String operationId = requiredText(json, "operationId");
    String author = json.has("author") ? json.get("author").asText() : "web-editor";
    Instant timestamp =
        json.has("timestamp") ? Instant.parse(json.get("timestamp").asText()) : Instant.now();
    return switch (type) {
      case "ConnectPorts" -> {
        JsonNode edgeJson = json.get("edge");
        if (edgeJson == null) {
          throw new IllegalArgumentException("ConnectPorts requires 'edge' field");
        }
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
        JsonNode edgeJson = json.get("disconnectedEdge");
        if (edgeJson == null) {
          throw new IllegalArgumentException("DisconnectPorts requires 'disconnectedEdge' field");
        }
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
        WorkflowOperation.PropertyTarget propertyTarget =
            Arrays.stream(WorkflowOperation.PropertyTarget.values())
                .filter(t -> t.name().equals(target))
                .findFirst()
                .orElseThrow(
                    () -> new IllegalArgumentException("Unknown PropertyTarget: " + target));
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

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException("Missing required field: " + field);
    }
    return value.asText();
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

  /**
   * JSON response body for rejected operations (HTTP 422).
   *
   * @param violations list of validation violation messages
   */
  public record ViolationsResponse(List<String> violations) {

    public ViolationsResponse {
      violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
    }
  }
}
