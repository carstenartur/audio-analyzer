package org.hammer.audio.workflow.editor.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.PresenceState;
import org.hammer.audio.workflow.collaboration.WorkflowSessionEventHub;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.collaboration.identity.InMemoryCollaborationIdentityStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkflowSessionEventHttpAdapterTest {

  private static final String SESSION_ID = "session.stream";
  private static final OperationActor OWNER =
      new OperationActor("actor.owner", "user.owner", "Owner");

  private WorkflowSessionEventHub eventHub;
  private WorkflowSessionRegistry registry;
  private MockMvc mvc;

  @BeforeEach
  void configureSpringMvc() {
    eventHub = new WorkflowSessionEventHub(8, 4);
    registry = new WorkflowSessionRegistry(eventHub);
    registry.create(
        SESSION_ID,
        CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
        OWNER,
        new Workflow("workflow.stream", "Stream", List.of(), List.of()));
    mvc =
        MockMvcBuilders.standaloneSetup(
                new WorkflowSessionEventHttpAdapter(
                    registry, CollaborationIdentityTestSupport.identities()))
            .defaultRequest(
                get("/")
                    .cookie(CollaborationIdentityTestSupport.cookies())
                    .param("actorId", "actor.owner"))
            .setControllerAdvice(new WorkflowApiExceptionHandler())
            .build();
  }

  @Test
  void sseStreamFlushesConnectionThenUsesSequenceIdsAndCompletesAfterSessionClose()
      throws Exception {
    long cursor = eventHub.currentSequence(SESSION_ID);
    MvcResult stream =
        mvc.perform(
                get("/workflow/sessions/{sessionId}/events", SESSION_ID)
                    .queryParam("afterSequence", Long.toString(cursor)))
            .andExpect(request().asyncStarted())
            .andReturn();

    Node node = new Node("node.input", "input", "Input", List.of(), List.of(), Metadata.empty());
    registry.applyOperation(
        SESSION_ID,
        CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
        OWNER,
        new WorkflowOperation.CreateNode(
            "operation.input", Instant.parse("2026-07-17T03:00:00Z"), OWNER.actorId(), node));
    registry.close(SESSION_ID, OWNER.actorId());

    MvcResult completed =
        mvc.perform(asyncDispatch(stream))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn();
    String body = completed.getResponse().getContentAsString();
    assertTrue(body.contains(":connected"));
    assertTrue(body.indexOf(":connected") < body.indexOf("event:OPERATION_ACCEPTED"));
    assertTrue(body.contains("event:OPERATION_ACCEPTED"));
    assertTrue(body.contains("event:SESSION_CLOSED"));
    assertTrue(body.contains("\"operationId\":\"operation.input\""));
    assertTrue(body.contains("id:" + (cursor + 1)));
    assertFalse(body.contains("userId"));
    assertFalse(body.contains("user.owner"));
    assertFalse(body.contains("adapter-test-credential"));
  }

  @Test
  void leavingEndsTheSubscriptionBeforeOtherParticipantsFutureOperations() throws Exception {
    var guest = new OperationActor("actor.guest", "user.guest", "Guest");
    registry.join(SESSION_ID, guest);
    MvcResult stream =
        mvc.perform(
                get("/workflow/sessions/{sessionId}/events", SESSION_ID)
                    .param("actorId", guest.actorId())
                    .queryParam(
                        "afterSequence", Long.toString(eventHub.currentSequence(SESSION_ID))))
            .andExpect(request().asyncStarted())
            .andReturn();
    registry.leave(SESSION_ID, guest.actorId());
    Node node =
        new Node("node.private", "input", "Private", List.of(), List.of(), Metadata.empty());
    registry.applyOperation(
        SESSION_ID,
        CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
        OWNER,
        new WorkflowOperation.CreateNode(
            "operation.after-leave", Instant.now(), OWNER.actorId(), node));
    registry.close(SESSION_ID, OWNER.actorId());
    MvcResult completed = mvc.perform(asyncDispatch(stream)).andExpect(status().isOk()).andReturn();
    String body = completed.getResponse().getContentAsString();
    assertFalse(body.contains("operation.after-leave"));
    assertFalse(body.contains("node.private"));
    assertFalse(body.contains("event:SESSION_CLOSED"));
  }

  @Test
  void credentialExpiryStopsAnAlreadyOpenStreamBeforeSendingAnotherFrame() throws Exception {
    Clock clock = mock(Clock.class);
    Instant issuedAt = Instant.parse("2026-11-20T00:00:00Z");
    when(clock.instant()).thenReturn(issuedAt);
    var identities =
        new CollaborationIdentityService(new InMemoryCollaborationIdentityStore(), false, clock);
    var response = new MockHttpServletResponse();
    var publicActor = identities.issue(new MockHttpServletRequest(), response, null, "Pat");
    var http = new MockHttpServletRequest();
    http.setCookies(response.getCookies());
    var owner = identities.requireActor(http, publicActor.actorId());
    registry.create(
        "session.expiry",
        CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
        owner,
        new Workflow("workflow.expiry", "Expiry", List.of(), List.of()));
    MockMvc expiryMvc =
        MockMvcBuilders.standaloneSetup(new WorkflowSessionEventHttpAdapter(registry, identities))
            .build();
    MvcResult stream =
        expiryMvc
            .perform(
                get("/workflow/sessions/session.expiry/events")
                    .cookie(response.getCookies())
                    .param("actorId", owner.actorId())
                    .param(
                        "afterSequence", Long.toString(eventHub.currentSequence("session.expiry"))))
            .andExpect(request().asyncStarted())
            .andReturn();
    when(clock.instant()).thenReturn(issuedAt.plus(Duration.ofDays(30)));
    Node node =
        new Node("node.after-expiry", "input", "Private", List.of(), List.of(), Metadata.empty());
    registry.applyOperation(
        "session.expiry",
        CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
        owner,
        new WorkflowOperation.CreateNode(
            "operation.after-expiry", Instant.now(), owner.actorId(), node));
    registry.close("session.expiry", owner.actorId());
    MvcResult completed =
        expiryMvc.perform(asyncDispatch(stream)).andExpect(status().isOk()).andReturn();
    assertFalse(completed.getResponse().getContentAsString().contains("operation.after-expiry"));
    assertFalse(completed.getResponse().getContentAsString().contains("node.after-expiry"));
  }

  @Test
  void replacementBetweenMembershipCheckAndSubscribeCannotExposeTheNewStream() throws Exception {
    WorkflowSessionRegistry original = registry;
    AtomicBoolean replaced = new AtomicBoolean();
    OperationActor replacementOwner =
        new OperationActor("actor.replacement", "user.replacement", "Replacement");
    registry =
        mock(
            WorkflowSessionRegistry.class,
            withSettings()
                .spiedInstance(original)
                .defaultAnswer(
                    invocation -> {
                      Object result = invocation.callRealMethod();
                      String method = invocation.getMethod().getName();
                      if ((method.equals("inspect") || method.equals("subscribe"))
                          && replaced.compareAndSet(false, true)) {
                        original.close(SESSION_ID, OWNER.actorId());
                        original.create(
                            SESSION_ID,
                            CollaborationMode.PRIVATE_WORKSPACE,
                            replacementOwner,
                            new Workflow(
                                "workflow.replacement-secret", "Private", List.of(), List.of()));
                      }
                      return result;
                    }));
    mvc =
        MockMvcBuilders.standaloneSetup(
                new WorkflowSessionEventHttpAdapter(
                    registry, CollaborationIdentityTestSupport.identities()))
            .defaultRequest(
                get("/")
                    .cookie(CollaborationIdentityTestSupport.cookies())
                    .param("actorId", OWNER.actorId()))
            .setControllerAdvice(new WorkflowApiExceptionHandler())
            .build();

    MvcResult stream =
        mvc.perform(get("/workflow/sessions/{sessionId}/events", SESSION_ID))
            .andExpect(request().asyncStarted())
            .andReturn();
    try {
      assertTrue(replaced.get());
      // Check the live replacement before closing it, even if the old wire is already empty.
      assertEquals(0, eventHub.subscriberCount(SESSION_ID));
      String body = stream.getResponse().getContentAsString();
      assertFalse(body.contains("workflow.replacement-secret"));
      assertFalse(body.contains("actor.replacement"));
    } finally {
      original.close(SESSION_ID, replacementOwner.actorId());
      if (stream.getRequest().isAsyncStarted()) {
        stream.getRequest().getAsyncContext().complete();
      }
    }
  }

  @Test
  void replayGapSendsCanonicalSnapshotInsteadOfPartialHistory() throws Exception {
    WorkflowSessionEventHub smallHub = new WorkflowSessionEventHub(2, 4);
    WorkflowSessionRegistry smallRegistry = new WorkflowSessionRegistry(smallHub);
    smallRegistry.create(
        SESSION_ID,
        CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
        OWNER,
        new Workflow("workflow.stream", "Stream", List.of(), List.of()));
    for (int index = 0; index < 3; index++) {
      smallRegistry.updatePresence(
          SESSION_ID,
          OWNER,
          new PresenceState(
              OWNER.actorId(),
              Instant.parse("2026-07-17T03:00:0" + index + "Z"),
              Map.of("cursor.x", Integer.toString(index))));
    }
    MockMvc smallMvc =
        MockMvcBuilders.standaloneSetup(
                new WorkflowSessionEventHttpAdapter(
                    smallRegistry, CollaborationIdentityTestSupport.identities()))
            .defaultRequest(
                get("/")
                    .cookie(CollaborationIdentityTestSupport.cookies())
                    .param("actorId", "actor.owner"))
            .setControllerAdvice(new WorkflowApiExceptionHandler())
            .build();

    MvcResult stream =
        smallMvc
            .perform(
                get("/workflow/sessions/{sessionId}/events", SESSION_ID)
                    .header("Last-Event-ID", "0"))
            .andExpect(request().asyncStarted())
            .andReturn();
    smallRegistry.close(SESSION_ID, OWNER.actorId());

    MvcResult completed =
        smallMvc.perform(asyncDispatch(stream)).andExpect(status().isOk()).andReturn();
    String body = completed.getResponse().getContentAsString();
    assertTrue(body.contains(":connected"));
    assertTrue(body.contains("event:SNAPSHOT"));
    assertTrue(body.contains("\"workflowId\":\"workflow.stream\""));
  }
}
