package org.hammer.audio.workflow.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.WorkflowOperationLog;
import org.junit.jupiter.api.Test;

class CollaborativeWorkflowSessionServiceTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void personalUndo_rejectsWhenItWouldRevertAnotherActorsLaterWork() {
    CollaborativeWorkflowSessionService service =
        newService(CollaborationMode.SHARED_SESSION_PERSONAL_UNDO, event -> {});
    OperationActor alice = new OperationActor("alice", "alice", "Alice");
    OperationActor bob = new OperationActor("bob", "bob", "Bob");

    service.applyOperation(
        envelope(
            CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
            rename("op.alice", T0, "alice", "Input", "Input A"),
            alice));
    service.applyOperation(
        envelope(
            CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
            rename("op.bob", T0.plusSeconds(1), "bob", "Input A", "Input B"),
            bob));

    IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.undo(alice));
    assertTrue(ex.getMessage().contains("Personal undo would revert operation from actor bob"));
    assertEquals("Input B", findNode(service.currentWorkflow(), "node.input").label());
  }

  @Test
  void sharedUndo_requiresExplicitTargetAndReportsRevertedActorAndOperation() {
    CollaborativeWorkflowSessionService service =
        newService(CollaborationMode.SHARED_SESSION_SHARED_UNDO, event -> {});
    OperationActor alice = new OperationActor("alice", "alice", "Alice");
    OperationActor bob = new OperationActor("bob", "bob", "Bob");

    service.applyOperation(
        envelope(
            CollaborationMode.SHARED_SESSION_SHARED_UNDO,
            rename("op.alice", T0, "alice", "Input", "Input A"),
            alice));

    CollaborativeWorkflowSessionService.UndoResult result = service.undo(bob, "op.alice");

    assertEquals(UndoScope.SHARED, result.scope());
    assertEquals("bob", result.requestedByActor());
    assertEquals("alice", result.revertedActorId());
    assertEquals("op.alice", result.revertedOperationId());
    assertEquals("Input", findNode(service.currentWorkflow(), "node.input").label());
  }

  @Test
  void publishesEventsOnlyAfterEventWasAppendedToOutbox() {
    class OrderCheckingOutbox implements WorkflowEventOutbox {
      private final InMemoryWorkflowEventOutbox delegate = new InMemoryWorkflowEventOutbox();
      private boolean appendCalled;

      @Override
      public OutboxEntry append(WorkflowCollaborationEvent event) {
        appendCalled = true;
        return delegate.append(event);
      }

      @Override
      public List<OutboxEntry> pending() {
        return delegate.pending();
      }

      @Override
      public void markPublished(String entryId) {
        delegate.markPublished(entryId);
      }
    }
    OrderCheckingOutbox outbox = new OrderCheckingOutbox();
    List<WorkflowCollaborationEvent> published = new ArrayList<>();
    CollaborativeWorkflowSessionService service =
        new CollaborativeWorkflowSessionService(
            "session-1",
            CollaborationMode.PRIVATE_WORKSPACE,
            new WorkflowOperationLog(initialWorkflow()),
            outbox,
            event -> {
              assertTrue(outbox.appendCalled, "publish must happen only after outbox append");
              published.add(event);
            });

    OperationActor alice = new OperationActor("alice", "alice", "Alice");
    service.applyOperation(
        envelope(
            CollaborationMode.PRIVATE_WORKSPACE,
            rename("op.alice", T0, "alice", "Input", "Input A"),
            alice));

    assertEquals(1, published.size());
    assertEquals(0, outbox.pending().size(), "published outbox entry should be marked as delivered");
  }

  @Test
  void presenceStateIsNonSemanticAndDoesNotChangeWorkflowOperationHistory() {
    CollaborativeWorkflowSessionService service =
        newService(CollaborationMode.SHARED_SESSION_PERSONAL_UNDO, event -> {});
    assertEquals(0, service.operations().size());

    service.updatePresence(
        new PresenceState(
            "alice", Instant.parse("2026-01-01T12:00:00Z"), Map.of("cursor.x", "100")));

    assertEquals(0, service.operations().size(), "presence updates must not touch semantic history");
    assertEquals(1, service.presenceSnapshot().size());
    assertEquals("100", service.presenceSnapshot().get("alice").attributes().get("cursor.x"));
  }

  private static CollaborativeWorkflowSessionService newService(
      CollaborationMode mode, WorkflowEventBus bus) {
    return new CollaborativeWorkflowSessionService(
        "session-1",
        mode,
        new WorkflowOperationLog(initialWorkflow()),
        new InMemoryWorkflowEventOutbox(),
        bus);
  }

  private static WorkflowOperationEnvelope envelope(
      CollaborationMode mode, WorkflowOperation operation, OperationActor actor) {
    return new WorkflowOperationEnvelope("session-1", mode, actor, operation, T0);
  }

  private static WorkflowOperation.RenameNode rename(
      String operationId, Instant timestamp, String author, String previousLabel, String newLabel) {
    return new WorkflowOperation.RenameNode(
        operationId, timestamp, author, "node.input", previousLabel, newLabel);
  }

  private static Workflow initialWorkflow() {
    return new Workflow(
        "workflow.demo",
        "Demo",
        List.of(new Node("node.input", "in", "Input", List.of(), List.of())),
        List.of());
  }

  private static Node findNode(Workflow workflow, String nodeId) {
    return workflow.nodes().stream()
        .filter(node -> node.id().equals(nodeId))
        .findFirst()
        .orElseThrow();
  }
}
