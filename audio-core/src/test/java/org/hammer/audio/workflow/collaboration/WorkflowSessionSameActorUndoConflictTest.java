package org.hammer.audio.workflow.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.collaboration.WorkflowSessionException.Code;
import org.junit.jupiter.api.Test;

class WorkflowSessionSameActorUndoConflictTest {

  private static final OperationActor OWNER =
      new OperationActor("actor.owner", "user.owner", "Owner");
  private static final Instant BASE_TIME = Instant.parse("2026-07-19T04:00:00Z");

  @Test
  void laterOwnEditOnAffectedObjectBlocksExplicitPersonalUndo() {
    WorkflowSessionRegistry registry = registry(CollaborationMode.SHARED_SESSION_PERSONAL_UNDO);
    createNode(registry, "operation.create", "Original", 0);
    renameNode(registry, "operation.rename", "Original", "Renamed", 1);

    WorkflowUndoPreview preview = registry.previewUndo("session.undo", OWNER, "operation.create");

    assertFalse(preview.safe());
    assertEquals(List.of("operation.rename"), blockerIds(preview));
    assertCode(
        Code.UNDO_CONFLICT,
        () ->
            registry.undo(
                "session.undo",
                new UndoWorkflowCommand("undo.create", OWNER, 2, "operation.create", null)));
    assertEquals("Renamed", node(registry).label());
  }

  @Test
  void laterOwnEditOnAffectedObjectBlocksRedo() {
    WorkflowSessionRegistry registry = registry(CollaborationMode.PRIVATE_WORKSPACE);
    createNode(registry, "operation.create", "Original", 0);
    renameNode(registry, "operation.rename", "Original", "Renamed", 1);
    WorkflowHistoryCommandResult undone =
        registry.undo("session.undo", new UndoWorkflowCommand("undo.rename", OWNER, 2, null, null));
    renameNode(registry, "operation.rename.after-undo", "Original", "Changed again", 3);

    WorkflowUndoConflictException exception =
        assertThrows(
            WorkflowUndoConflictException.class,
            () ->
                registry.redo(
                    "session.undo",
                    new RedoWorkflowCommand("redo.rename", OWNER, 4, undone.operationId())));

    assertEquals(Code.UNDO_CONFLICT, exception.code());
    assertEquals(List.of("operation.rename.after-undo"), blockerIds(exception));
    assertEquals("Changed again", node(registry).label());
  }

  private static WorkflowSessionRegistry registry(CollaborationMode mode) {
    WorkflowSessionRegistry registry = new WorkflowSessionRegistry();
    registry.create("session.undo", mode, OWNER, emptyWorkflow());
    return registry;
  }

  private static void createNode(
      WorkflowSessionRegistry registry, String operationId, String label, long expectedRevision) {
    registry.applyOperation(
        "session.undo",
        registry.inspect("session.undo").mode(),
        OWNER,
        expectedRevision,
        new WorkflowOperation.CreateNode(
            operationId,
            BASE_TIME.plusSeconds(expectedRevision),
            OWNER.actorId(),
            new Node("node.shared", "test", label, List.of(), List.of(), Metadata.empty())));
  }

  private static void renameNode(
      WorkflowSessionRegistry registry,
      String operationId,
      String previousLabel,
      String newLabel,
      long expectedRevision) {
    registry.applyOperation(
        "session.undo",
        registry.inspect("session.undo").mode(),
        OWNER,
        expectedRevision,
        new WorkflowOperation.RenameNode(
            operationId,
            BASE_TIME.plusSeconds(expectedRevision),
            OWNER.actorId(),
            "node.shared",
            previousLabel,
            newLabel));
  }

  private static Node node(WorkflowSessionRegistry registry) {
    return registry.workflow("session.undo").nodes().stream()
        .filter(candidate -> candidate.id().equals("node.shared"))
        .findFirst()
        .orElseThrow();
  }

  private static List<String> blockerIds(WorkflowUndoPreview preview) {
    return preview.blockingOperations().stream()
        .map(WorkflowUndoPreview.BlockingOperation::operationId)
        .toList();
  }

  private static List<String> blockerIds(WorkflowUndoConflictException exception) {
    return exception.blockingOperations().stream()
        .map(WorkflowUndoPreview.BlockingOperation::operationId)
        .toList();
  }

  private static void assertCode(
      Code expected, org.junit.jupiter.api.function.Executable executable) {
    WorkflowSessionException exception = assertThrows(WorkflowSessionException.class, executable);
    assertEquals(expected, exception.code());
  }

  private static Workflow emptyWorkflow() {
    return new Workflow("workflow.undo", "Undo workflow", List.of(), List.of());
  }
}
