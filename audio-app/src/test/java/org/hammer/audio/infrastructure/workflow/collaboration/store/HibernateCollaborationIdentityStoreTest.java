package org.hammer.audio.infrastructure.workflow.collaboration.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.WorkflowSessionEventHub;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.editor.http.CollaborationIdentityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HibernateCollaborationIdentityStoreTest {
  @TempDir Path directory;

  @Test
  void identityAndHistorySurviveRestartWithoutPersistingBearerSecret() {
    var properties = WorkflowOutboxStoreTestSupport.fileProperties(directory.resolve("identity"));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setUserPrincipal(() -> "account/alice");
    MockHttpServletResponse response = new MockHttpServletResponse();
    OperationActor actor;
    try (var provider = WorkflowOutboxStoreTestSupport.provider(properties)) {
      var store = new HibernateCollaborationIdentityStore(provider.getSessionFactory());
      var service = new CollaborationIdentityService(store, false, Clock.systemUTC());
      var issued = service.issue(request, response, null, "Alice");
      request.setCookies(response.getCookies());
      actor = service.requireActor(request, issued.actorId());
      var sessionStore = new HibernateWorkflowSessionStateStore(provider.getSessionFactory());
      var registry = new WorkflowSessionRegistry(new WorkflowSessionEventHub(), sessionStore);
      registry.create(
          "identity.restart",
          CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
          actor,
          new Workflow("workflow.identity", "Identity", List.of(), List.of()));
      registry.applyOperation(
          "identity.restart",
          CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
          actor,
          new WorkflowOperation.CreateNode(
              "identity.operation",
              Instant.now(),
              actor.actorId(),
              new Node("node.identity", "input", "Input", List.of(), List.of(), Metadata.empty())));
      assertFalse(
          store
              .find(actor.actorId())
              .orElseThrow()
              .toString()
              .contains(response.getCookies()[0].getValue()));
      assertFalse(
          sessionStore
              .operations("identity.restart")
              .toString()
              .contains(response.getCookies()[0].getValue()));
    }
    try (var provider = WorkflowOutboxStoreTestSupport.provider(properties)) {
      var service =
          new CollaborationIdentityService(
              new HibernateCollaborationIdentityStore(provider.getSessionFactory()),
              false,
              Clock.systemUTC());
      var restored = service.requireActor(request, actor.actorId());
      assertEquals(actor, restored);
      assertEquals("Alice", service.historyActor(actor.actorId()).displayName());
      assertTrue(service.historyActor(actor.actorId()).verified());
      assertEquals(null, service.historyActor(actor.actorId()).accountHandle());
      var sessionStore = new HibernateWorkflowSessionStateStore(provider.getSessionFactory());
      var registry = new WorkflowSessionRegistry(new WorkflowSessionEventHub(), sessionStore);
      assertTrue(registry.inspect("identity.restart").participants().isEmpty());
      registry.join("identity.restart", restored);
      registry.join("identity.restart", restored);
      assertEquals(1, registry.inspect("identity.restart").participants().size());
      assertEquals(
          actor.actorId(),
          registry
              .history("identity.restart", restored, null, 10)
              .operations()
              .getFirst()
              .actorId());
    }
  }
}
