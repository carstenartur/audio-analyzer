package org.hammer.audio.workflow.editor.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkflowSessionHttpAdapterTest {

  private static final String OWNER_JSON =
      """
      {"actorId":"actor.owner","userId":"user.owner","displayName":"Owner"}
      """;
  private static final String GUEST_JSON =
      """
      {"actorId":"actor.guest","userId":"user.guest","displayName":"Guest"}
      """;

  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient client = HttpClient.newHttpClient();
  private WorkflowSessionHttpAdapter adapter;
  private URI baseUri;

  @BeforeEach
  void startServer() throws IOException {
    adapter = new WorkflowSessionHttpAdapter(new WorkflowSessionRegistry());
    adapter.start(0);
    baseUri = URI.create("http://127.0.0.1:" + adapter.port() + "/workflow/sessions");
  }

  @AfterEach
  void stopServer() {
    adapter.stop(0);
  }

  @Test
  void createJoinInspectProjectionLeaveAndClose() throws Exception {
    HttpResponse<String> created =
        request(
            "POST",
            baseUri,
            """
            {
              "sessionId":"session.shared",
              "mode":"SHARED_SESSION_PERSONAL_UNDO",
              "actor":%s,
              "workflowId":"workflow.shared",
              "workflowName":"Shared workflow"
            }
            """
                .formatted(OWNER_JSON));
    assertEquals(201, created.statusCode());

    HttpResponse<String> joined =
        request("POST", baseUri.resolve("/workflow/sessions/session.shared/join"), GUEST_JSON);
    assertEquals(200, joined.statusCode());
    assertEquals(2, mapper.readTree(joined.body()).path("participants").size());

    HttpResponse<String> inspected =
        request("GET", baseUri.resolve("/workflow/sessions/session.shared"), null);
    assertEquals(200, inspected.statusCode());
    assertEquals("workflow.shared", mapper.readTree(inspected.body()).path("workflowId").asText());

    HttpResponse<String> projection =
        request("GET", baseUri.resolve("/workflow/sessions/session.shared/projection"), null);
    assertEquals(200, projection.statusCode());
    assertEquals("workflow.shared", mapper.readTree(projection.body()).path("workflowId").asText());

    HttpResponse<String> left =
        request(
            "POST",
            baseUri.resolve("/workflow/sessions/session.shared/leave"),
            "{\"actorId\":\"actor.guest\"}");
    assertEquals(200, left.statusCode());
    assertEquals(1, mapper.readTree(left.body()).path("participants").size());

    HttpResponse<String> closed =
        request(
            "DELETE",
            baseUri.resolve("/workflow/sessions/session.shared"),
            "{\"actorId\":\"actor.owner\"}");
    assertEquals(200, closed.statusCode());
    assertEquals(
        404,
        request("GET", baseUri.resolve("/workflow/sessions/session.shared"), null).statusCode());
  }

  @Test
  void duplicateCreateUnknownSessionAndPrivateJoinReturnStableErrors() throws Exception {
    String createPrivate =
        """
        {
          "sessionId":"session.private",
          "mode":"PRIVATE_WORKSPACE",
          "actor":%s
        }
        """
            .formatted(OWNER_JSON);
    assertEquals(201, request("POST", baseUri, createPrivate).statusCode());
    assertEquals(409, request("POST", baseUri, createPrivate).statusCode());

    HttpResponse<String> privateJoin =
        request("POST", baseUri.resolve("/workflow/sessions/session.private/join"), GUEST_JSON);
    assertEquals(409, privateJoin.statusCode());
    assertTrue(
        mapper.readTree(privateJoin.body()).path("error").asText().contains("Private workspace"));

    HttpResponse<String> unknown =
        request("GET", baseUri.resolve("/workflow/sessions/missing"), null);
    assertEquals(404, unknown.statusCode());
  }

  @Test
  void duplicateJoinIsIdempotentAndActorMetadataMismatchIsRejected() throws Exception {
    createSharedSession();
    assertEquals(
        200,
        request("POST", baseUri.resolve("/workflow/sessions/session.shared/join"), GUEST_JSON)
            .statusCode());
    HttpResponse<String> duplicate =
        request("POST", baseUri.resolve("/workflow/sessions/session.shared/join"), GUEST_JSON);
    assertEquals(200, duplicate.statusCode());
    assertEquals(2, mapper.readTree(duplicate.body()).path("participants").size());

    JsonNode guest = mapper.readTree(GUEST_JSON);
    ((com.fasterxml.jackson.databind.node.ObjectNode) guest).put("displayName", "Changed");
    assertEquals(
        400,
        request(
                "POST",
                baseUri.resolve("/workflow/sessions/session.shared/join"),
                mapper.writeValueAsString(guest))
            .statusCode());
  }

  private void createSharedSession() throws Exception {
    HttpResponse<String> response =
        request(
            "POST",
            baseUri,
            """
            {
              "sessionId":"session.shared",
              "mode":"SHARED_SESSION_PERSONAL_UNDO",
              "actor":%s
            }
            """
                .formatted(OWNER_JSON));
    assertEquals(201, response.statusCode());
  }

  private HttpResponse<String> request(String method, URI uri, String body) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri).header("Content-Type", "application/json");
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder.method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}
