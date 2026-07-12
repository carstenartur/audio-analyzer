package org.hammer.audio.workflow.editor.http;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class WorkflowSessionHttpAdapterTest {

  private static final String OWNER_JSON =
      """
      {"actorId":"actor.owner","userId":"user.owner","displayName":"Owner"}
      """;
  private static final String GUEST_JSON =
      """
      {"actorId":"actor.guest","userId":"user.guest","displayName":"Guest"}
      """;

  private MockMvc mvc;

  @BeforeEach
  void configureSpringMvc() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mvc =
        MockMvcBuilders.standaloneSetup(
                new WorkflowSessionHttpAdapter(new WorkflowSessionRegistry()))
            .setControllerAdvice(new WorkflowApiExceptionHandler())
            .setValidator(validator)
            .build();
  }

  @Test
  void createJoinInspectProjectionLeaveAndClose() throws Exception {
    mvc.perform(
            post("/workflow/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "sessionId":"session.shared",
                      "mode":"SHARED_SESSION_PERSONAL_UNDO",
                      "actor":%s,
                      "workflowId":"workflow.shared",
                      "workflowName":"Shared workflow"
                    }
                    """
                        .formatted(OWNER_JSON)))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/workflow/sessions/session.shared"))
        .andExpect(jsonPath("$.workflowId").value("workflow.shared"));

    mvc.perform(
            post("/workflow/sessions/session.shared/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content(GUEST_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.participants.length()").value(2));

    mvc.perform(get("/workflow/sessions/session.shared"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowId").value("workflow.shared"));

    mvc.perform(get("/workflow/sessions/session.shared/projection"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowId").value("workflow.shared"));

    mvc.perform(
            post("/workflow/sessions/session.shared/leave")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actorId\":\"actor.guest\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.participants.length()").value(1));

    mvc.perform(
            delete("/workflow/sessions/session.shared")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actorId\":\"actor.owner\"}"))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    mvc.perform(get("/workflow/sessions/session.shared"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
  }

  @Test
  void duplicateCreatePrivateJoinAndUnknownSessionUseProblemDetails() throws Exception {
    String createPrivate =
        """
        {
          "sessionId":"session.private",
          "mode":"PRIVATE_WORKSPACE",
          "actor":%s
        }
        """
            .formatted(OWNER_JSON);

    mvc.perform(
            post("/workflow/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPrivate))
        .andExpect(status().isCreated());

    mvc.perform(
            post("/workflow/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPrivate))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SESSION_ALREADY_EXISTS"));

    mvc.perform(
            post("/workflow/sessions/session.private/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content(GUEST_JSON))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PRIVATE_WORKSPACE_ACCESS_DENIED"));

    mvc.perform(get("/workflow/sessions/missing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.sessionId").value("missing"));
  }

  @Test
  void beanValidationAndMetadataMismatchHaveStableResponses() throws Exception {
    mvc.perform(
            post("/workflow/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "sessionId":"",
                      "mode":"SHARED_SESSION_PERSONAL_UNDO",
                      "actor":{"actorId":"","userId":"user","displayName":""}
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://audio-analyzer.dev/problems/invalid-request"))
        .andExpect(jsonPath("$.violations").isArray());

    createSharedSession();
    mvc.perform(
            post("/workflow/sessions/session.shared/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content(GUEST_JSON))
        .andExpect(status().isOk());

    mvc.perform(
            post("/workflow/sessions/session.shared/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"actorId":"actor.guest","userId":"user.guest","displayName":"Changed"}
                    """))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ACTOR_METADATA_MISMATCH"));
  }

  private void createSharedSession() throws Exception {
    mvc.perform(
            post("/workflow/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "sessionId":"session.shared",
                      "mode":"SHARED_SESSION_PERSONAL_UNDO",
                      "actor":%s
                    }
                    """
                        .formatted(OWNER_JSON)))
        .andExpect(status().isCreated());
  }
}
