package org.hammer.audio.workflow.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.collaboration.WorkflowSessionException.Code;
import org.junit.jupiter.api.Test;

class WorkflowSessionCommandIdOwnershipTest {

  private static final OperationActor OWNER =
      new OperationActor("actor.owner", "user.owner", "Owner");
  private static final OperationActor GUEST =
      new OperationActor("actor.guest", "user.guest", "Guest");
  private static final Instant BASE_TIME = Instant.parse("2026-07-19T05:00:00Z");

  @Test
  void undoCommandIdCannotBeReusedByAnotherActor() {
    WorkflowSessionRegistry registry = registry("session.undo-command");
    createNode(registry, "session.undo-command", "operation.create", 0);
    registry.undo(
        "session.undo-command",
        new UndoWorkflowCommand("command.shared", OWNER, 1, null, null));

    assertCode(
        Code.DUPLICATE_OPERATION_ID,
        () ->
            registry.undo(
                "session.undo-command",
                new UndoWorkflowCommand("command.shared", GUEST, 2, null, null)));
  }

  @Test
  void redoCommandIdCannotBeReusedByAnotherActor() {
    WorkflowSessionRegistry registry = registry("session.redo-command");
    createNode(registry, "session.redo-command", "operation.create", 0);
    WorkflowHistoryCommandResult undone =
        registry.undo(
            "session.redo-command",
            new UndoWorkflowCommand("command.undo", OWNER, 1, null, null));
    registry.redo(
        "session.redo-command",
        new RedoWorkflowCommand("command.shared-redo", OWNER, 2, undone.operationId()));

    assertCode(
        Code.DUPLICATE_OPERATION_ID,
        () ->
            registry.redo(
                "session.redo-command",
                new RedoWorkflowCommand(
                    "command.shared-redo", GUEST, 3, undone.operationId())));
  }

  private static WorkflowSessionRegistry registry(String sessionId) {
    WorkflowSessionRegistry registry = new WorkflowSessionRegistry();
    registry.create(
        sessionId,
        CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
        OWNER,
        new Workflow("workflow.command-id", "Command id workflow", List.of(), List.of()));
    registry.join(sessionId, GUEST);
    return registry;
  }

  private static void createNode(
      WorkflowSessionRegistry registry,
      String sessionId,
      String operationId,
      long expectedRevision) {
    registry.applyOperation(
        sessionId,
        CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
        OWNER,
        expectedRevision,
        new WorkflowOperation.CreateNode(
            operationId,
            BASE_TIME.plusSeconds(expectedRevision),
            OWNER.actorId(),
            new Node("node.shared", "test", "Shared", List.of(), List.of(), Metadata.empty())));
  }

  private static void assertCode(
      Code expected, org.junit.jupiter.api.function.Executable executable) {
    WorkflowSessionException exception = assertThrows(WorkflowSessionException.class, executable);
    assertEquals(expected, exception.code());
  }
}
