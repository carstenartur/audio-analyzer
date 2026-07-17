package org.hammer.audio.workflow.editor.http;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.PresenceState;
import org.hammer.audio.workflow.collaboration.WorkflowSessionEventHub;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
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
        MockMvcBuilders.standaloneSetup(new WorkflowSessionEventHttpAdapter(registry, eventHub))
            .setControllerAdvice(new WorkflowApiExceptionHandler())
            .build();
  }

  @Test
  void sseStreamUsesSequenceIdsAndCompletesAfterSessionClose() throws Exception {
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
    assertTrue(body.contains("event:OPERATION_ACCEPTED"));
    assertTrue(body.contains("event:SESSION_CLOSED"));
    assertTrue(body.contains("\"operationId\":\"operation.input\""));
    assertTrue(body.contains("id:" + (cursor + 1)));
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
                new WorkflowSessionEventHttpAdapter(smallRegistry, smallHub))
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
    assertTrue(body.contains("event:SNAPSHOT"));
    assertTrue(body.contains("\"workflowId\":\"workflow.stream\""));
  }
}
