package org.hammer.audio.workflow.editor.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hammer.audio.workflow.collaboration.WorkflowSessionEventHub;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class WorkflowSessionUndoHttpAdapterTest {

  private static final String OWNER_JSON =
      """
      {"actorId":"actor.owner","userId":"user.owner","displayName":"Owner"}
      """;
  private static final String GUEST_JSON =
      """
      {"actorId":"actor.guest","userId":"user.guest","displayName":"Guest"}
      """;

  private WorkflowSessionRegistry registry;
  private WorkflowSessionEventHub eventHub;
  private MockMvc mvc;

  @BeforeEach
  void configureSpringMvc() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    eventHub = new WorkflowSessionEventHub(32, 8);
    registry = new WorkflowSessionRegistry(eventHub);
    mvc =
        MockMvcBuilders.standaloneSetup(new WorkflowSessionHttpAdapter(registry))
            .setControllerAdvice(new WorkflowApiExceptionHandler())
            .setValidator(validator)
            .build();
  }

  @Test
  void previewUndoRetryAndRedoReturnCanonicalCommandState() throws Exception {
    createSession();
    createNode(OWNER_JSON, "operation.create", 0);

    String previewJson =
        mvc.perform(
                post("/workflow/sessions/session.undo/undo/preview")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"actor\":" + OWNER_JSON + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.targetOperationId").value("operation.create"))
            .andExpect(jsonPath("$.revision").value(1))
            .andExpect(jsonPath("$.safe").value(true))
            .andExpect(jsonPath("$.blockingOperations.length()").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String previewId =
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .readTree(previewJson)
            .get("previewId")
            .asText();

    String undoBody =
        """
        {
          "commandId":"undo.command",
          "actor":%s,
          "expectedRevision":1,
          "targetOperationId":"operation.create",
          "previewId":"%s"
        }
        """
            .formatted(OWNER_JSON, previewId);
    String undoOperationId =
        mvc.perform(
                post("/workflow/sessions/session.undo/undo")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(undoBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.commandKind").value("UNDO"))
            .andExpect(jsonPath("$.commandId").value("undo.command"))
            .andExpect(jsonPath("$.targetOperationId").value("operation.create"))
            .andExpect(jsonPath("$.projection.nodes.length()").value(0))
            .andExpect(jsonPath("$.revision").value(2))
            .andExpect(jsonPath("$.sequence").value(4))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String acceptedUndoOperationId =
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .readTree(undoOperationId)
            .get("operationId")
            .asText();

    mvc.perform(
            post("/workflow/sessions/session.undo/undo")
                .contentType(MediaType.APPLICATION_JSON)
                .content(undoBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.operationId").value(acceptedUndoOperationId))
        .andExpect(jsonPath("$.revision").value(2));

    mvc.perform(
            post("/workflow/sessions/session.undo/redo")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "commandId":"redo.command",
                      "actor":%s,
                      "expectedRevision":2,
                      "targetUndoOperationId":"%s"
                    }
                    """
                        .formatted(OWNER_JSON, acceptedUndoOperationId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.commandKind").value("REDO"))
        .andExpect(jsonPath("$.targetOperationId").value(acceptedUndoOperationId))
        .andExpect(jsonPath("$.projection.nodes.length()").value(1))
        .andExpect(jsonPath("$.revision").value(3))
        .andExpect(jsonPath("$.sequence").value(5));

    assertEquals(
        "REDO", eventHub.replay("session.undo", 4).getFirst().attributes().get("commandKind"));
  }

  @Test
  void blockedUndoReturnsTargetAndRemoteBlocker() throws Exception {
    createSession();
    createNode(OWNER_JSON, "operation.create", 0);
    joinGuest();
    mvc.perform(
            post("/workflow/sessions/session.undo/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "mode":"SHARED_SESSION_PERSONAL_UNDO",
                      "actor":%s,
                      "expectedRevision":1,
                      "operation":{
                        "type":"UpdateProperty",
                        "operationId":"operation.guest.property",
                        "target":"NODE",
                        "targetId":"node.input",
                        "propertyKey":"note",
                        "newValue":"guest"
                      }
                    }
                    """
                        .formatted(GUEST_JSON)))
        .andExpect(status().isOk());

    mvc.perform(
            post("/workflow/sessions/session.undo/undo")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "commandId":"undo.blocked",
                      "actor":%s,
                      "expectedRevision":2,
                      "targetOperationId":"operation.create"
                    }
                    """
                        .formatted(OWNER_JSON)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("UNDO_CONFLICT"))
        .andExpect(jsonPath("$.targetOperationId").value("operation.create"))
        .andExpect(
            jsonPath("$.blockingOperations[0].operationId").value("operation.guest.property"))
        .andExpect(jsonPath("$.blockingOperations[0].actorId").value("actor.guest"))
        .andExpect(jsonPath("$.blockingOperations[0].conflictingObjectIds[0]").value("node.input"));
  }

  private void createSession() throws Exception {
    mvc.perform(
            post("/workflow/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "sessionId":"session.undo",
                      "mode":"SHARED_SESSION_PERSONAL_UNDO",
                      "actor":%s
                    }
                    """
                        .formatted(OWNER_JSON)))
        .andExpect(status().isCreated());
  }

  private void joinGuest() throws Exception {
    mvc.perform(
            post("/workflow/sessions/session.undo/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content(GUEST_JSON))
        .andExpect(status().isOk());
  }

  private void createNode(String actorJson, String operationId, long revision) throws Exception {
    mvc.perform(
            post("/workflow/sessions/session.undo/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "mode":"SHARED_SESSION_PERSONAL_UNDO",
                      "actor":%s,
                      "expectedRevision":%d,
                      "operation":{
                        "type":"CreateNode",
                        "operationId":"%s",
                        "catalogType":"recording-input",
                        "nodeId":"node.input"
                      }
                    }
                    """
                        .formatted(actorJson, revision, operationId)))
        .andExpect(status().isOk());
  }
}
