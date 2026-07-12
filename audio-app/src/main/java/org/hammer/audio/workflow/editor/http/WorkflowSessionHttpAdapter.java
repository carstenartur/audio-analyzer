package org.hammer.audio.workflow.editor.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;

/** HTTP adapter for collaboration-session lifecycle from issue #241. */
public final class WorkflowSessionHttpAdapter {

  private static final String BASE_PATH = "/workflow/sessions";
  private static final String APPLICATION_JSON = "application/json; charset=utf-8";
  private static final int HTTP_OK = 200;
  private static final int HTTP_CREATED = 201;
  private static final int HTTP_BAD_REQUEST = 400;
  private static final int HTTP_NOT_FOUND = 404;
  private static final int HTTP_CONFLICT = 409;
  private static final int HTTP_METHOD_NOT_ALLOWED = 405;

  private final WorkflowSessionRegistry registry;
  private final ObjectMapper mapper = new ObjectMapper();
  private HttpServer server;

  public WorkflowSessionHttpAdapter(WorkflowSessionRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  /** Starts a standalone lifecycle API server. Port {@code 0} requests an ephemeral port. */
  public void start(int port) throws IOException {
    HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);
    registerContexts(httpServer);
    httpServer.start();
  }

  /** Registers the session API on an existing HTTP server. */
  public void registerContexts(HttpServer httpServer) {
    server = Objects.requireNonNull(httpServer, "httpServer");
    httpServer.createContext(BASE_PATH, this::handleSessions);
  }

  /** Returns the bound port after {@link #start(int)}. */
  public int port() {
    if (server == null) {
      throw new IllegalStateException("Session HTTP adapter is not started");
    }
    return server.getAddress().getPort();
  }

  /** Stops the standalone or shared HTTP server. */
  public void stop(int delaySeconds) {
    if (server != null) {
      server.stop(delaySeconds);
    }
  }

  private void handleSessions(HttpExchange exchange) throws IOException {
    try {
      List<String> segments = pathSegments(exchange.getRequestURI().getPath());
      if (segments.size() == 2) {
        handleCollection(exchange);
        return;
      }
      if (segments.size() < 3) {
        sendError(exchange, HTTP_NOT_FOUND, "Unknown session endpoint");
        return;
      }
      String sessionId = segments.get(2);
      if (segments.size() == 3) {
        handleSession(exchange, sessionId);
        return;
      }
      if (segments.size() == 4 && "join".equals(segments.get(3))) {
        requireMethod(exchange, "POST");
        sendJson(exchange, HTTP_OK, registry.join(sessionId, readActor(exchange)));
        return;
      }
      if (segments.size() == 4 && "leave".equals(segments.get(3))) {
        requireMethod(exchange, "POST");
        JsonNode body = readJson(exchange);
        sendJson(exchange, HTTP_OK, registry.leave(sessionId, requiredText(body, "actorId")));
        return;
      }
      if (segments.size() == 4 && "projection".equals(segments.get(3))) {
        requireMethod(exchange, "GET");
        sendJson(exchange, HTTP_OK, registry.projection(sessionId));
        return;
      }
      sendError(exchange, HTTP_NOT_FOUND, "Unknown session endpoint");
    } catch (MethodNotAllowedException ex) {
      sendError(exchange, HTTP_METHOD_NOT_ALLOWED, ex.getMessage());
    } catch (IllegalArgumentException ex) {
      int status = ex.getMessage() != null && ex.getMessage().startsWith("Unknown session:")
          ? HTTP_NOT_FOUND
          : HTTP_BAD_REQUEST;
      sendError(exchange, status, ex.getMessage());
    } catch (IllegalStateException ex) {
      sendError(exchange, HTTP_CONFLICT, ex.getMessage());
    }
  }

  private void handleCollection(HttpExchange exchange) throws IOException {
    if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      sendJson(exchange, HTTP_OK, registry.sessions());
      return;
    }
    requireMethod(exchange, "POST");
    JsonNode body = readJson(exchange);
    String sessionId = requiredText(body, "sessionId");
    CollaborationMode mode = CollaborationMode.valueOf(requiredText(body, "mode"));
    OperationActor owner = actorFrom(body.path("actor"));
    String workflowId = textOrDefault(body, "workflowId", "workflow." + sessionId);
    String workflowName = textOrDefault(body, "workflowName", "Workflow " + sessionId);
    Workflow workflow = new Workflow(workflowId, workflowName, List.of(), List.of());
    sendJson(exchange, HTTP_CREATED, registry.create(sessionId, mode, owner, workflow));
  }

  private void handleSession(HttpExchange exchange, String sessionId) throws IOException {
    if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      sendJson(exchange, HTTP_OK, registry.inspect(sessionId));
      return;
    }
    requireMethod(exchange, "DELETE");
    JsonNode body = readJson(exchange);
    registry.close(sessionId, requiredText(body, "actorId"));
    sendJson(exchange, HTTP_OK, new ClosedSessionResponse(sessionId));
  }

  private OperationActor readActor(HttpExchange exchange) throws IOException {
    return actorFrom(readJson(exchange));
  }

  private static OperationActor actorFrom(JsonNode node) {
    return new OperationActor(
        requiredText(node, "actorId"),
        requiredText(node, "userId"),
        requiredText(node, "displayName"));
  }

  private JsonNode readJson(HttpExchange exchange) throws IOException {
    byte[] bytes = exchange.getRequestBody().readAllBytes();
    if (bytes.length == 0) {
      throw new IllegalArgumentException("JSON request body is required");
    }
    try {
      return mapper.readTree(bytes);
    } catch (IOException ex) {
      throw new IllegalArgumentException("Invalid JSON: " + ex.getMessage(), ex);
    }
  }

  private static String requiredText(JsonNode node, String field) {
    if (node == null || node.isMissingNode() || !node.has(field) || node.get(field).asText().isBlank()) {
      throw new IllegalArgumentException("Missing or blank field: " + field);
    }
    return node.get(field).asText();
  }

  private static String textOrDefault(JsonNode node, String field, String fallback) {
    return node.has(field) && !node.get(field).asText().isBlank() ? node.get(field).asText() : fallback;
  }

  private static List<String> pathSegments(String path) {
    return java.util.Arrays.stream(path.split("/"))
        .filter(segment -> !segment.isBlank())
        .toList();
  }

  private static void requireMethod(HttpExchange exchange, String expected) {
    if (!expected.equalsIgnoreCase(exchange.getRequestMethod())) {
      throw new MethodNotAllowedException("Expected HTTP " + expected);
    }
  }

  private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
    byte[] body = mapper.writeValueAsBytes(value);
    exchange.getResponseHeaders().set("Content-Type", APPLICATION_JSON);
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(body);
    }
  }

  private void sendError(HttpExchange exchange, int status, String message) throws IOException {
    sendJson(exchange, status, new ErrorResponse(message == null ? "Request failed" : message));
  }

  private record ErrorResponse(String error) {}

  private record ClosedSessionResponse(String sessionId) {}

  private static final class MethodNotAllowedException extends RuntimeException {
    MethodNotAllowedException(String message) {
      super(message);
    }
  }
}
