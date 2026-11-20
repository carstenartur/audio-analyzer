package org.hammer.audio.workflow.execution.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.collaboration.identity.InMemoryCollaborationIdentityStore;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
import org.hammer.audio.workflow.editor.http.CollaborationIdentityService;
import org.hammer.audio.workflow.editor.http.WorkflowApiExceptionHandler;
import org.hammer.audio.workflow.execution.SimulationWorkflowExecutionBackend;
import org.hammer.audio.workflow.execution.WorkflowRunService;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;
import org.hammer.audio.workflow.store.WorkflowSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkflowRunIdentitySecurityTest {
  private static final String PRIVATE_SESSION = "session.private";
  private CollaborationIdentityService identities;
  private WorkflowSessionRegistry sessions;
  private WorkflowRunService runs;
  private MockMvc mvc;
  private Client owner;
  private Client editor;
  private Client outsider;

  @BeforeEach
  void configure() {
    identities =
        new CollaborationIdentityService(
            new InMemoryCollaborationIdentityStore(), false, Clock.systemUTC());
    owner = client("Owner");
    editor = client("Editor");
    outsider = client("Outsider");
    sessions = new WorkflowSessionRegistry();
    sessions.create(
        PRIVATE_SESSION,
        CollaborationMode.PRIVATE_WORKSPACE,
        owner.actor(),
        workflow("workflow.private"));
    sessions.create(
        "session.shared",
        CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
        owner.actor(),
        workflow("workflow.shared"));
    sessions.join("session.shared", editor.actor());
    VersionedWorkflowStore store = mock(VersionedWorkflowStore.class);
    var stored = workflow("workflow.public-stored");
    when(store.loadAtCommit(new CommitId("commit.public")))
        .thenReturn(
            new WorkflowSnapshot(stored.id(), new WorkflowDslSerializer().serialize(stored)));
    runs =
        new WorkflowRunService(
            sessions, store, new SimulationWorkflowExecutionBackend(), Runnable::run);
    mvc =
        MockMvcBuilders.standaloneSetup(new WorkflowRunHttpAdapter(runs, sessions, identities))
            .setControllerAdvice(
                new WorkflowRunHttpExceptionHandler(), new WorkflowApiExceptionHandler())
            .build();
  }

  @Test
  void liveSessionRunStartRequiresCredentialAndJoinedMembership() throws Exception {
    mvc.perform(
            post("/workflow/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header(CollaborationIdentityService.ACTOR_HEADER, owner.actor().actorId())
                .content(startBody("command.no-proof", PRIVATE_SESSION)))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/workflow/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie(outsider.cookies())
                .header(CollaborationIdentityService.ACTOR_HEADER, owner.actor().actorId())
                .content(startBody("command.copied-id", PRIVATE_SESSION)))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/workflow/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie(outsider.cookies())
                .header(CollaborationIdentityService.ACTOR_HEADER, outsider.actor().actorId())
                .content(startBody("command.not-joined", PRIVATE_SESSION)))
        .andExpect(status().isForbidden());
    assertEquals(0, runs.runs().size());
  }

  @Test
  void sessionRunReadResultListAndCancelCannotBypassSessionPrivacy() throws Exception {
    String runId = start(owner, "command.private", PRIVATE_SESSION);
    mvc.perform(get("/workflow/runs/{runId}", runId)).andExpect(status().isForbidden());
    mvc.perform(
            get("/workflow/runs/{runId}/result", runId)
                .cookie(outsider.cookies())
                .header(CollaborationIdentityService.ACTOR_HEADER, outsider.actor().actorId()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/workflow/runs/{runId}/cancel", runId)
                .cookie(outsider.cookies())
                .header(CollaborationIdentityService.ACTOR_HEADER, outsider.actor().actorId()))
        .andExpect(status().isForbidden());
    mvc.perform(get("/workflow/runs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(
            get("/workflow/runs")
                .cookie(outsider.cookies())
                .header(CollaborationIdentityService.ACTOR_HEADER, outsider.actor().actorId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(
            get("/workflow/runs/{runId}/result", runId)
                .cookie(owner.cookies())
                .header(CollaborationIdentityService.ACTOR_HEADER, owner.actor().actorId()))
        .andExpect(status().isOk());
    mvc.perform(
            get("/workflow/runs/{runId}/result", runId)
                .cookie(owner.cookies()[0], outsider.cookies()[0])
                .header(CollaborationIdentityService.ACTOR_HEADER, outsider.actor().actorId()))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/workflow/runs/{runId}/result", runId)
                .cookie(owner.cookies()[0], outsider.cookies()[0])
                .header(CollaborationIdentityService.ACTOR_HEADER, owner.actor().actorId()))
        .andExpect(status().isOk());
  }

  @Test
  void membershipGrantsAccessWhileCreatorAndOriginalOwnerRetainTheirRunAfterClose()
      throws Exception {
    String runId = start(editor, "command.shared", "session.shared");
    sessions.join("session.shared", outsider.actor());
    mvc.perform(
            get("/workflow/runs/{runId}/result", runId)
                .cookie(outsider.cookies())
                .header(CollaborationIdentityService.ACTOR_HEADER, outsider.actor().actorId()))
        .andExpect(status().isOk());
    // Replaying another member's command must not transfer retained run ownership.
    assertEquals(runId, start(outsider, "command.shared", "session.shared"));
    sessions.leave("session.shared", outsider.actor().actorId());
    mvc.perform(
            get("/workflow/runs/{runId}/result", runId)
                .cookie(outsider.cookies())
                .header(CollaborationIdentityService.ACTOR_HEADER, outsider.actor().actorId()))
        .andExpect(status().isForbidden());
    sessions.leave("session.shared", editor.actor().actorId());
    sessions.close("session.shared", owner.actor().actorId());
    for (Client retained : List.of(owner, editor)) {
      mvc.perform(
              get("/workflow/runs/{runId}/result", runId)
                  .cookie(retained.cookies())
                  .header(CollaborationIdentityService.ACTOR_HEADER, retained.actor().actorId()))
          .andExpect(status().isOk());
    }
  }

  @Test
  void reusingSessionIdAndStartCommandDoesNotExposeAnEarlierPrivateRun() throws Exception {
    String runId = start(owner, "command.original", PRIVATE_SESSION);
    sessions.close(PRIVATE_SESSION, owner.actor().actorId());
    sessions.create(
        PRIVATE_SESSION,
        CollaborationMode.PRIVATE_WORKSPACE,
        outsider.actor(),
        workflow("workflow.replacement"));
    mvc.perform(
            get("/workflow/runs/{runId}/result", runId)
                .cookie(outsider.cookies())
                .header(CollaborationIdentityService.ACTOR_HEADER, outsider.actor().actorId()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/workflow/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie(outsider.cookies())
                .header(CollaborationIdentityService.ACTOR_HEADER, outsider.actor().actorId())
                .content(startBody("command.original", PRIVATE_SESSION)))
        .andExpect(status().isForbidden());
  }

  @Test
  void sessionReplacementAfterAuthorizationCannotChangeCapturedRunWorkflow() throws Exception {
    AtomicBoolean replaced = new AtomicBoolean();
    runs =
        mock(
            WorkflowRunService.class,
            withSettings()
                .spiedInstance(runs)
                .defaultAnswer(
                    invocation -> {
                      if (invocation.getMethod().getName().equals("start")
                          && replaced.compareAndSet(false, true)) {
                        sessions.close(PRIVATE_SESSION, owner.actor().actorId());
                        sessions.create(
                            PRIVATE_SESSION,
                            CollaborationMode.PRIVATE_WORKSPACE,
                            outsider.actor(),
                            workflow("workflow.replacement-secret"));
                      }
                      return invocation.callRealMethod();
                    }));
    mvc =
        MockMvcBuilders.standaloneSetup(new WorkflowRunHttpAdapter(runs, sessions, identities))
            .setControllerAdvice(
                new WorkflowRunHttpExceptionHandler(), new WorkflowApiExceptionHandler())
            .build();

    mvc.perform(
            post("/workflow/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie(owner.cookies())
                .header(CollaborationIdentityService.ACTOR_HEADER, owner.actor().actorId())
                .content(startBody("command.replaced-during-start", PRIVATE_SESSION)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.workflowId").value("workflow.private"));
    assertTrue(replaced.get());
    assertEquals("workflow.private", runs.runs().getFirst().workflowId());
  }

  @Test
  void storedCommitRunsKeepTheirExistingAccessContract() throws Exception {
    mvc.perform(
            post("/workflow/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
        {"startCommandId":"command.stored","sourceKind":"STORED_COMMIT","commitId":"commit.public"}
        """))
        .andExpect(status().isAccepted());
    String runId = runs.runs().getFirst().runId();
    mvc.perform(get("/workflow/runs/{runId}/result", runId)).andExpect(status().isOk());
    mvc.perform(get("/workflow/runs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  private String start(Client client, String commandId, String sessionId) throws Exception {
    mvc.perform(
            post("/workflow/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie(client.cookies())
                .header(CollaborationIdentityService.ACTOR_HEADER, client.actor().actorId())
                .content(startBody(commandId, sessionId)))
        .andExpect(status().isAccepted());
    return runs.runs().stream()
        .filter(run -> run.startCommandId().equals(commandId))
        .findFirst()
        .orElseThrow()
        .runId();
  }

  private Client client(String name) {
    var response = new MockHttpServletResponse();
    var publicActor = identities.issue(new MockHttpServletRequest(), response, null, name);
    var request = new MockHttpServletRequest();
    request.setCookies(response.getCookies());
    return new Client(
        identities.requireActor(request, publicActor.actorId()), response.getCookies());
  }

  private static Workflow workflow(String id) {
    return new Workflow(id, "Run identity", List.of(), List.of());
  }

  private static String startBody(String commandId, String sessionId) {
    return """
        {"startCommandId":"%s","sourceKind":"LIVE_SESSION","sessionId":"%s","expectedRevision":0}
        """
        .formatted(commandId, sessionId);
  }

  private record Client(OperationActor actor, Cookie[] cookies) {}
}
