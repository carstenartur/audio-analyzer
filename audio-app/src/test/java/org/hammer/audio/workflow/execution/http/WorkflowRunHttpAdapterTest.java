package org.hammer.audio.workflow.execution.http;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.editor.http.WorkflowApiExceptionHandler;
import org.hammer.audio.workflow.execution.SimulationWorkflowExecutionBackend;
import org.hammer.audio.workflow.execution.WorkflowRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class WorkflowRunHttpAdapterTest {

  private MockMvc mvc;
  private WorkflowRunService runs;

  @BeforeEach
  void configureSpringMvc() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    WorkflowSessionRegistry sessions = new WorkflowSessionRegistry();
    OperationActor owner = new OperationActor("actor.owner", "user.owner", "Owner");
    sessions.create(
        "session.test",
        CollaborationMode.PRIVATE_WORKSPACE,
        owner,
        new Workflow("workflow.test", "Test Workflow", List.of(), List.of()));
    runs =
        new WorkflowRunService(
            sessions, null, new SimulationWorkflowExecutionBackend(), Runnable::run);
    mvc =
        MockMvcBuilders.standaloneSetup(new WorkflowRunHttpAdapter(runs))
            .setControllerAdvice(
                new WorkflowRunHttpExceptionHandler(), new WorkflowApiExceptionHandler())
            .setValidator(validator)
            .build();
  }

  @Test
  void startsListsInspectsAndReturnsSimulationResult() throws Exception {
    mvc.perform(
            post("/workflow/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "startCommandId":"command.http",
                      "sourceKind":"LIVE_SESSION",
                      "sessionId":"session.test",
                      "expectedRevision":0
                    }
                    """))
        .andExpect(status().isAccepted())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.state").value("COMPLETED"))
        .andExpect(jsonPath("$.mode").value("SIMULATION"))
        .andExpect(jsonPath("$.source.kind").value("LIVE_SESSION"))
        .andExpect(jsonPath("$.source.semanticRevision").value(0))
        .andExpect(jsonPath("$.fingerprint").value(matchesPattern("[0-9a-f]{64}")));

    String runId = runs.runs().getFirst().runId();
    mvc.perform(get("/workflow/runs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].runId").value(runId));
    mvc.perform(get("/workflow/runs/{runId}", runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.planId").isNotEmpty());
    mvc.perform(get("/workflow/runs/{runId}/result", runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.overallStatus").value("COMPLETED"))
        .andExpect(jsonPath("$.artifacts.backendMode").value("SIMULATION"));
  }

  @Test
  void unknownAndNotReadyRunsUseStableProblemDetails() throws Exception {
    mvc.perform(get("/workflow/runs/run.missing"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("UNKNOWN_RUN"));

    mvc.perform(
            post("/workflow/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "startCommandId":"command.invalid",
                      "sourceKind":"STORED_COMMIT"
                    }
                    """))
        .andExpect(status().isBadRequest());
  }
}
