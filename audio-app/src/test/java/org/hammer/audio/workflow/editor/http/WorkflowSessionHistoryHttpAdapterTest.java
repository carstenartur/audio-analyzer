package org.hammer.audio.workflow.editor.http;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WorkflowSessionHistoryHttpAdapterTest {

  private static final String OWNER_JSON =
      """
      {"actorId":"actor.owner","userId":"user.owner","displayName":"Owner"}
      """;

  private MockMvc mvc;

  @BeforeEach
  void configureSpringMvc() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    WorkflowSessionRegistry registry =
        new WorkflowSessionRegistry(new WorkflowSessionEventHub(32, 8));
    mvc =
        MockMvcBuilders.standaloneSetup(new WorkflowSessionHttpAdapter(registry))
            .setControllerAdvice(new WorkflowApiExceptionHandler())
            .setValidator(validator)
            .build();
  }

  @Test
  void historyCapabilitiesAndRedoPreviewRemainServerAuthoritative() throws Exception {
    createSession();
    createNode();

    mvc.perform(
            post("/workflow/sessions/session.history/history/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actor\":" + OWNER_JSON + ",\"limit\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentRevision").value(1))
        .andExpect(jsonPath("$.nextBeforeRevision").doesNotExist())
        .andExpect(jsonPath("$.operations.length()").value(1))
        .andExpect(jsonPath("$.operations[0].operationId").value("operation.create"))
        .andExpect(jsonPath("$.operations[0].commandKind").value("NORMAL"))
        .andExpect(jsonPath("$.operations[0].reconstructible").value(true))
        .andExpect(jsonPath("$.operations[0].activeUndoTarget").value(true))
        .andExpect(jsonPath("$.operations[0].occurredAt").isNotEmpty());

    mvc.perform(
            post("/workflow/sessions/session.history/history/capabilities")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actor\":" + OWNER_JSON + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revision").value(1))
        .andExpect(jsonPath("$.personalUndoPermitted").value(true))
        .andExpect(jsonPath("$.personalUndo.status").value("AVAILABLE"))
        .andExpect(jsonPath("$.personalUndo.operation.operationId").value("operation.create"))
        .andExpect(jsonPath("$.redo").doesNotExist())
        .andExpect(jsonPath("$.sharedUndoPermitted").value(false));

    mvc.perform(
            post("/workflow/sessions/session.history/undo/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actor\":" + OWNER_JSON + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetOperationId").value("operation.create"))
        .andExpect(jsonPath("$.targetOccurredAt").isNotEmpty())
        .andExpect(jsonPath("$.safe").value(true));

    String undoJson =
        mvc.perform(
                post("/workflow/sessions/session.history/undo")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "commandId":"command.undo",
                          "actor":%s,
                          "expectedRevision":1
                        }
                        """
                            .formatted(OWNER_JSON)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.commandKind").value("UNDO"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode undoResponse = JsonMapper.builder().build().readTree(undoJson);
    String undoOperationId = undoResponse.get("operationId").asText();

    mvc.perform(
            post("/workflow/sessions/session.history/history/capabilities")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actor\":" + OWNER_JSON + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revision").value(2))
        .andExpect(jsonPath("$.personalUndo").doesNotExist())
        .andExpect(jsonPath("$.redo.status").value("AVAILABLE"))
        .andExpect(jsonPath("$.redo.operation.operationId").value(undoOperationId));

    mvc.perform(
            post("/workflow/sessions/session.history/redo/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "actor":%s,
                      "targetUndoOperationId":"%s"
                    }
                    """
                        .formatted(OWNER_JSON, undoOperationId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetUndoOperationId").value(undoOperationId))
        .andExpect(jsonPath("$.targetOccurredAt").isNotEmpty())
        .andExpect(jsonPath("$.revision").value(2))
        .andExpect(jsonPath("$.safe").value(true));

    mvc.perform(get("/workflow/sessions/session.history"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revision").value(2))
        .andExpect(jsonPath("$.sequence").value(4));
  }

  @Test
  void invalidHistoryPageSizeReturnsStructuredBadRequest() throws Exception {
    createSession();

    mvc.perform(
            post("/workflow/sessions/session.history/history/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actor\":" + OWNER_JSON + ",\"limit\":101}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://audio-analyzer.dev/problems/invalid-request"))
        .andExpect(jsonPath("$.violations[0].field").value("limit"));
  }

  private void createSession() throws Exception {
    mvc.perform(
            post("/workflow/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "sessionId":"session.history",
                      "mode":"SHARED_SESSION_PERSONAL_UNDO",
                      "actor":%s
                    }
                    """
                        .formatted(OWNER_JSON)))
        .andExpect(status().isCreated());
  }

  private void createNode() throws Exception {
    mvc.perform(
            post("/workflow/sessions/session.history/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "mode":"SHARED_SESSION_PERSONAL_UNDO",
                      "actor":%s,
                      "expectedRevision":0,
                      "operation":{
                        "type":"CreateNode",
                        "operationId":"operation.create",
                        "catalogType":"recording-input",
                        "nodeId":"node.input"
                      }
                    }
                    """
                        .formatted(OWNER_JSON)))
        .andExpect(status().isOk());
  }
}
