package org.hammer.audio.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.List;
import org.hammer.audio.acquisition.health.ExperimentHealthState;
import org.hammer.audio.experiment.document.ExperimentDocumentService;
import org.hammer.audio.experiment.document.workspace.ExperimentDocumentWorkspaceService;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperationLog;
import org.hammer.audio.workflow.WorkflowValidator;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.collaboration.identity.InMemoryCollaborationIdentityStore;
import org.hammer.audio.workflow.editor.WorkflowEditorService;
import org.hammer.audio.workflow.editor.http.CollaborationIdentityException;
import org.hammer.audio.workflow.editor.http.CollaborationIdentityService;
import org.hammer.audio.workflow.editor.http.WorkflowApiExceptionHandler;
import org.hammer.audio.workflow.execution.SimulationWorkflowExecutionBackend;
import org.hammer.audio.workflow.execution.WorkflowRunModels.SourceKind;
import org.hammer.audio.workflow.execution.WorkflowRunService;
import org.hammer.audio.workflow.execution.http.WorkflowRunApiModels.StartRunRequest;
import org.hammer.audio.workflow.execution.http.WorkflowRunHttpAdapter;
import org.hammer.audio.workflow.execution.http.WorkflowRunHttpExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ExperimentHealthHttpAdapterTest {

  @Test
  void selectedSessionHealthRequiresBoundIdentityAndCurrentMembership() throws Exception {
    var sessions = new WorkflowSessionRegistry();
    var identities =
        new CollaborationIdentityService(
            new InMemoryCollaborationIdentityStore(), false, Clock.systemUTC());
    var request = authenticated(identities);
    var owner = identities.requireActor(request, null);
    sessions.create("private.session", CollaborationMode.PRIVATE_WORKSPACE, owner, workflow());
    var runs =
        new WorkflowRunService(
            sessions, null, new SimulationWorkflowExecutionBackend(), Runnable::run);
    var adapter = adapter(sessions, identities, runs);

    assertThrows(
        CollaborationIdentityException.class,
        () -> adapter.status("private.session", 0L, null, null, new MockHttpServletRequest()));
    var outsider = authenticated(identities);
    assertThrows(
        CollaborationIdentityException.class,
        () -> adapter.status("private.session", 0L, null, null, outsider));
    var mvc =
        MockMvcBuilders.standaloneSetup(adapter)
            .setControllerAdvice(new WorkflowApiExceptionHandler())
            .build();
    mvc.perform(
            get("/workflow/experiment-health")
                .param("sessionId", "private.session")
                .param("revision", "0"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("COLLABORATION_IDENTITY_REQUIRED"));
    mvc.perform(
            get("/workflow/experiment-health")
                .param("sessionId", "private.session")
                .param("revision", "0")
                .header(
                    CollaborationIdentityService.ACTOR_HEADER,
                    outsider.getHeader(CollaborationIdentityService.ACTOR_HEADER))
                .cookie(outsider.getCookies())
                .principal(outsider.getUserPrincipal()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("COLLABORATION_IDENTITY_REQUIRED"));

    var health = adapter.status("private.session", 0L, null, null, request);
    assertEquals(ExperimentHealthState.READY_WITH_WARNINGS, health.state());
    assertEquals("0", health.facts().get("workflow.revision"));
    assertFalse(
        health.facts().values().stream()
            .anyMatch(value -> value.contains("private-account@example.invalid")));
    assertTrue(runs.runs().isEmpty(), "Health queries must not start or retain executions");

    sessions.leave("private.session", owner.actorId());
    assertThrows(
        CollaborationIdentityException.class,
        () -> adapter.status("private.session", 0L, null, null, request));
  }

  @Test
  void changedDisplayedRevisionProducesABlockerBeforeExecution() throws Exception {
    var sessions = new WorkflowSessionRegistry();
    var identities =
        new CollaborationIdentityService(
            new InMemoryCollaborationIdentityStore(), false, Clock.systemUTC());
    var request = authenticated(identities);
    sessions.create(
        "private.session",
        CollaborationMode.PRIVATE_WORKSPACE,
        identities.requireActor(request, null),
        workflow());
    var runs =
        new WorkflowRunService(
            sessions, null, new SimulationWorkflowExecutionBackend(), Runnable::run);
    var adapter = adapter(sessions, identities, runs);
    var health = adapter.status("private.session", 1L, null, null, request);

    assertEquals(ExperimentHealthState.BLOCKED, health.state());
    assertFalse(health.startAllowed());
    assertTrue(
        health.findings().stream()
            .anyMatch(finding -> finding.code().equals("workflow.revision-changed")));
    assertTrue(runs.runs().isEmpty());
    MockMvcBuilders.standaloneSetup(adapter)
        .setControllerAdvice(new WorkflowApiExceptionHandler())
        .build()
        .perform(
            get("/workflow/experiment-health")
                .param("sessionId", "private.session")
                .param("revision", "1")
                .header(
                    CollaborationIdentityService.ACTOR_HEADER,
                    request.getHeader(CollaborationIdentityService.ACTOR_HEADER))
                .cookie(request.getCookies())
                .principal(request.getUserPrincipal()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("BLOCKED"));
  }

  @Test
  void runtimeHealthRetainsOriginalCreatorAndOwnerAfterCloseWithoutDisclosingAccountHandles()
      throws Exception {
    var sessions = new WorkflowSessionRegistry();
    var identities =
        new CollaborationIdentityService(
            new InMemoryCollaborationIdentityStore(), false, Clock.systemUTC());
    var ownerRequest = authenticated(identities, "Original owner");
    var creatorRequest = authenticated(identities, "Run creator");
    var observerRequest = authenticated(identities, "Current observer");
    var owner = identities.requireActor(ownerRequest, null);
    sessions.create(
        "shared.session", CollaborationMode.SHARED_SESSION_PERSONAL_UNDO, owner, workflow());
    sessions.join("shared.session", identities.requireActor(creatorRequest, null));
    sessions.join("shared.session", identities.requireActor(observerRequest, null));
    var runs =
        new WorkflowRunService(
            sessions, null, new SimulationWorkflowExecutionBackend(), Runnable::run);
    var runQueries = new WorkflowRunHttpAdapter(runs, sessions, identities);
    var response =
        runQueries
            .start(
                new StartRunRequest(
                    "command.creator", SourceKind.LIVE_SESSION, "shared.session", 0L, null),
                creatorRequest)
            .getBody();
    var documents = new ExperimentDocumentService(List.of());
    var editor =
        new WorkflowEditorService(new WorkflowOperationLog(workflow()), new WorkflowValidator());
    var adapter =
        new ExperimentHealthHttpAdapter(
            new ExperimentDocumentWorkspaceService(documents, editor),
            documents,
            sessions,
            identities,
            runs,
            runQueries);

    var observed = adapter.status(null, null, null, response.runId(), observerRequest);
    assertEquals("Run creator (AUTHENTICATED)", observed.facts().get("operator.attribution"));
    assertEquals("Original owner (AUTHENTICATED)", observed.facts().get("collaboration.owner"));
    assertEquals(ExperimentHealthState.INCOMPLETE, observed.state());
    assertEquals("false", observed.facts().get("evidence.complete"));
    assertFalse(observed.facts().toString().contains("private-account@example.invalid"));
    sessions.close("shared.session", owner.actorId());
    var retained = adapter.status(null, null, null, response.runId(), ownerRequest);
    assertEquals(
        observed.facts().get("operator.attribution"), retained.facts().get("operator.attribution"));
    assertEquals(
        observed.facts().get("collaboration.owner"), retained.facts().get("collaboration.owner"));
    assertThrows(
        CollaborationIdentityException.class,
        () -> adapter.status(null, null, null, response.runId(), observerRequest));
  }

  @Test
  void reviewedHttpStartRejectsReplacementGraphAndRetriesCapturedRunAfterClose() throws Exception {
    var sessions = new WorkflowSessionRegistry();
    var identities =
        new CollaborationIdentityService(
            new InMemoryCollaborationIdentityStore(), false, Clock.systemUTC());
    var request = authenticated(identities);
    var owner = identities.requireActor(request, null);
    sessions.create("private.session", CollaborationMode.PRIVATE_WORKSPACE, owner, workflow());
    var dispatched = new java.util.concurrent.atomic.AtomicInteger();
    var runs =
        new WorkflowRunService(
            sessions,
            null,
            new SimulationWorkflowExecutionBackend(),
            ignored -> dispatched.incrementAndGet());
    var runQueries = new WorkflowRunHttpAdapter(runs, sessions, identities);
    var adapter = adapter(sessions, identities, runs, runQueries);
    var mvc =
        MockMvcBuilders.standaloneSetup(adapter, runQueries)
            .setControllerAdvice(
                new WorkflowApiExceptionHandler(), new WorkflowRunHttpExceptionHandler())
            .build();
    String reviewed =
        adapter.status("private.session", 0L, null, null, request).facts().get("workflow.sha256");
    sessions.close("private.session", owner.actorId());
    sessions.create(
        "private.session",
        CollaborationMode.PRIVATE_WORKSPACE,
        owner,
        new Workflow("workflow.test", "Replacement workflow", List.of(), List.of()));
    String body = reviewedStartBody(reviewed);
    mvc.perform(
            post("/workflow/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header(CollaborationIdentityService.ACTOR_HEADER, owner.actorId())
                .cookie(request.getCookies())
                .principal(request.getUserPrincipal()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SOURCE_FINGERPRINT_MISMATCH"));
    assertEquals(0, dispatched.get());
    assertTrue(runs.runs().isEmpty());

    String current =
        adapter.status("private.session", 0L, null, null, request).facts().get("workflow.sha256");
    String accepted = reviewedStartBody(current);
    mvc.perform(
            post("/workflow/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(accepted)
                .header(CollaborationIdentityService.ACTOR_HEADER, owner.actorId())
                .cookie(request.getCookies())
                .principal(request.getUserPrincipal()))
        .andExpect(status().isAccepted());
    String runId = runs.runs().getFirst().runId();
    sessions.close("private.session", owner.actorId());
    mvc.perform(
            post("/workflow/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(accepted)
                .header(CollaborationIdentityService.ACTOR_HEADER, owner.actorId())
                .cookie(request.getCookies())
                .principal(request.getUserPrincipal()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.runId").value(runId));
    assertEquals(1, dispatched.get());
    assertEquals(1, runs.runs().size());
  }

  private static String reviewedStartBody(String fingerprint) {
    return """
        {"startCommandId":"command.reviewed","sourceKind":"LIVE_SESSION",
         "sessionId":"private.session","expectedRevision":0,"expectedFingerprint":"%s"}
        """
        .formatted(fingerprint);
  }

  private static ExperimentHealthHttpAdapter adapter(
      WorkflowSessionRegistry sessions,
      CollaborationIdentityService identities,
      WorkflowRunService runs) {
    return adapter(
        sessions, identities, runs, new WorkflowRunHttpAdapter(runs, sessions, identities));
  }

  private static ExperimentHealthHttpAdapter adapter(
      WorkflowSessionRegistry sessions,
      CollaborationIdentityService identities,
      WorkflowRunService runs,
      WorkflowRunHttpAdapter runQueries) {
    var documents = new ExperimentDocumentService(List.of());
    var editor =
        new WorkflowEditorService(new WorkflowOperationLog(workflow()), new WorkflowValidator());
    var workspace = new ExperimentDocumentWorkspaceService(documents, editor);
    return new ExperimentHealthHttpAdapter(
        workspace, documents, sessions, identities, runs, runQueries);
  }

  private static MockHttpServletRequest authenticated(CollaborationIdentityService identities) {
    return authenticated(identities, "Engineer");
  }

  private static MockHttpServletRequest authenticated(
      CollaborationIdentityService identities, String displayName) {
    var request = new MockHttpServletRequest();
    request.setUserPrincipal(() -> "private-account@example.invalid");
    var response = new MockHttpServletResponse();
    var identity = identities.issue(request, response, null, displayName);
    request.addHeader(CollaborationIdentityService.ACTOR_HEADER, identity.actorId());
    request.setCookies(response.getCookies());
    return request;
  }

  private static Workflow workflow() {
    return new Workflow("workflow.test", "Private workflow", List.of(), List.of());
  }
}
