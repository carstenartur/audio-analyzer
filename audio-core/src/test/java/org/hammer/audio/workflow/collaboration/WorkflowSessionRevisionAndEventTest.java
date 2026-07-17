package org.hammer.audio.workflow.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.junit.jupiter.api.Test;

class WorkflowSessionRevisionAndEventTest {

  private static final OperationActor OWNER =
      new OperationActor("actor.owner", "user.owner", "Owner");
  private static final OperationActor GUEST =
      new OperationActor("actor.guest", "user.guest", "Guest");

  @Test
  void acceptedOperationsAreRevisionCheckedAndPublishedInOrder() {
    BoundedWorkflowSessionEventHub hub = new BoundedWorkflowSessionEventHub(16);
    WorkflowSessionRegistry registry =
        new WorkflowSessionRegistry(new InMemoryWorkflowSessionStateStore(hub));
    registry.create(
        "session", CollaborationMode.SHARED_SESSION_PERSONAL_UNDO, OWNER, emptyWorkflow());
    registry.join("session", GUEST);

    List<WorkflowSessionEvent> delivered = new ArrayList<>();
    try (BoundedWorkflowSessionEventHub.Subscription ignored =
        hub.subscribe("session", delivered::add)) {
      WorkflowOperation operation = createNode("op-1", OWNER.actorId(), "node-1");
      WorkflowSessionRegistry.MutationResult result =
          registry.applyOperation(
              "session", CollaborationMode.SHARED_SESSION_PERSONAL_UNDO, OWNER, 0L, operation);
      assertEquals(1L, result.session().revision());
      assertEquals(1, result.workflow().nodes().size());
    }

    assertEquals(1, delivered.size());
    assertEquals(WorkflowSessionEvent.Type.OPERATION_ACCEPTED, delivered.getFirst().type());
    assertThrows(
        WorkflowSessionException.class,
        () ->
            registry.applyOperation(
                "session",
                CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
                OWNER,
                0L,
                createNode("op-stale", OWNER.actorId(), "node-2")));
  }

  @Test
  void personalUndoAndRedoAreSemanticOperations() {
    BoundedWorkflowSessionEventHub hub = new BoundedWorkflowSessionEventHub(16);
    WorkflowSessionRegistry registry =
        new WorkflowSessionRegistry(new InMemoryWorkflowSessionStateStore(hub));
    registry.create(
        "session", CollaborationMode.SHARED_SESSION_PERSONAL_UNDO, OWNER, emptyWorkflow());
    registry.applyOperation(
        "session",
        CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
        OWNER,
        0L,
        createNode("op-1", OWNER.actorId(), "node-1"));

    WorkflowSessionRegistry.MutationResult undone = registry.undo("session", OWNER, 1L, null);
    assertTrue(undone.workflow().nodes().isEmpty());
    assertEquals(2L, undone.session().revision());

    WorkflowSessionRegistry.MutationResult redone = registry.redo("session", OWNER, 2L);
    assertEquals(1, redone.workflow().nodes().size());
    assertEquals(3L, redone.session().revision());
    assertEquals(3, registry.operations("session").size());
  }

  private static WorkflowOperation createNode(String operationId, String author, String nodeId) {
    Node node = new Node(nodeId, "input", "Input", List.of(), List.of(), Metadata.empty());
    return new WorkflowOperation.CreateNode(operationId, Instant.now(), author, node);
  }

  private static Workflow emptyWorkflow() {
    return new Workflow("workflow.session", "Session workflow", List.of(), List.of());
  }
}
