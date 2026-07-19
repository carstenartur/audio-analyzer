package org.hammer.audio.workflow.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.collaboration.WorkflowSessionException.Code;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationCommandMetadata.Kind;
import org.junit.jupiter.api.Test;

class WorkflowSessionUndoRedoTest {

  private static final OperationActor OWNER =
      new OperationActor("actor.owner", "user.owner", "Owner");
  private static final OperationActor GUEST =
      new OperationActor("actor.guest", "user.guest", "Guest");
  private static final Instant BASE_TIME = Instant.parse("2026-07-18T21:00:00Z");

  @Test
  void personalUndoSelectsLatestOwnOperationAndRedoRestoresIt() {
    WorkflowSessionRegistry registry = registry(CollaborationMode.SHARED_SESSION_PERSONAL_UNDO);
    createNode(registry, OWNER, "operation.owner.create", "node.owner", "Owner node", 0);
    createNode(registry, GUEST, "operation.guest.create", "node.guest", "Guest node", 1);
    renameNode(
        registry,
        OWNER,
        "operation.owner.rename",
        "node.owner",
        "Owner node",
        "Renamed owner node",
        2);

    WorkflowUndoPreview preview = registry.previewUndo("session.undo", OWNER, null);
    assertEquals("operation.owner.rename", preview.targetOperationId());
    assertTrue(preview.safe());

    UndoWorkflowCommand command = new UndoWorkflowCommand("undo.owner.1", OWNER, 3, null, null);
    WorkflowHistoryCommandResult undone = registry.undo("session.undo", command);

    assertEquals(Kind.UNDO, undone.command().kind());
    assertEquals("operation.owner.rename", undone.command().targetOperationId());
    assertEquals("Owner node", node(registry, "node.owner").label());
    assertEquals(4, undone.revision());

    WorkflowHistoryCommandResult retried = registry.undo("session.undo", command);
    assertEquals(undone.operationId(), retried.operationId());
    assertEquals(4, retried.revision());
    assertEquals(4, registry.inspect("session.undo").operationCount());

    WorkflowHistoryCommandResult redone =
        registry.redo(
            "session.undo",
            new RedoWorkflowCommand("redo.owner.1", OWNER, 4, undone.operationId()));
    assertEquals(Kind.REDO, redone.command().kind());
    assertEquals("Renamed owner node", node(registry, "node.owner").label());
    assertEquals(5, redone.revision());
  }

  @Test
  void laterRemoteEditOnAffectedObjectBlocksPersonalUndo() {
    WorkflowSessionRegistry registry = registry(CollaborationMode.SHARED_SESSION_PERSONAL_UNDO);
    createNode(registry, OWNER, "operation.owner.create", "node.shared", "Shared", 0);
    renameNode(
        registry, GUEST, "operation.guest.rename", "node.shared", "Shared", "Guest rename", 1);

    WorkflowUndoPreview preview =
        registry.previewUndo("session.undo", OWNER, "operation.owner.create");

    assertFalse(preview.safe());
    assertEquals(List.of("operation.guest.rename"), blockerIds(preview));
    assertCode(
        Code.UNDO_CONFLICT,
        () ->
            registry.undo(
                "session.undo",
                new UndoWorkflowCommand(
                    "undo.owner.conflict", OWNER, 2, "operation.owner.create", null)));
    assertEquals("Guest rename", node(registry, "node.shared").label());
  }

  @Test
  void sharedUndoRequiresExplicitCurrentPreview() {
    WorkflowSessionRegistry registry = registry(CollaborationMode.SHARED_SESSION_SHARED_UNDO);
    createNode(registry, OWNER, "operation.owner.create", "node.owner", "Owner", 0);

    assertCode(Code.UNDO_TARGET_REQUIRED, () -> registry.previewUndo("session.undo", OWNER, null));

    WorkflowUndoPreview preview =
        registry.previewUndo("session.undo", OWNER, "operation.owner.create");
    assertTrue(preview.safe());
    assertCode(
        Code.UNDO_PREVIEW_REQUIRED,
        () ->
            registry.undo(
                "session.undo",
                new UndoWorkflowCommand(
                    "undo.shared.missing-preview", OWNER, 1, "operation.owner.create", null)));

    createNode(registry, GUEST, "operation.guest.create", "node.guest", "Guest", 1);
    assertCode(
        Code.UNDO_PREVIEW_STALE,
        () ->
            registry.undo(
                "session.undo",
                new UndoWorkflowCommand(
                    "undo.shared.stale", OWNER, 2, "operation.owner.create", preview.previewId())));

    WorkflowUndoPreview current =
        registry.previewUndo("session.undo", OWNER, "operation.owner.create");
    WorkflowHistoryCommandResult result =
        registry.undo(
            "session.undo",
            new UndoWorkflowCommand(
                "undo.shared.accepted", OWNER, 2, "operation.owner.create", current.previewId()));
    assertEquals(Kind.UNDO, result.command().kind());
    assertFalse(
        registry.workflow("session.undo").nodes().stream()
            .anyMatch(candidate -> candidate.id().equals("node.owner")));
  }

  @Test
  void redoCannotBeAppliedTwice() {
    WorkflowSessionRegistry registry = registry(CollaborationMode.PRIVATE_WORKSPACE);
    createNode(registry, OWNER, "operation.create", "node.one", "One", 0);
    WorkflowHistoryCommandResult undone =
        registry.undo("session.undo", new UndoWorkflowCommand("undo.once", OWNER, 1, null, null));
    registry.redo(
        "session.undo", new RedoWorkflowCommand("redo.once", OWNER, 2, undone.operationId()));

    assertCode(
        Code.REDO_ALREADY_APPLIED,
        () ->
            registry.redo(
                "session.undo",
                new RedoWorkflowCommand("redo.twice", OWNER, 3, undone.operationId())));
  }

  private static WorkflowSessionRegistry registry(CollaborationMode mode) {
    WorkflowSessionRegistry registry = new WorkflowSessionRegistry();
    registry.create("session.undo", mode, OWNER, emptyWorkflow());
    if (mode != CollaborationMode.PRIVATE_WORKSPACE) {
      registry.join("session.undo", GUEST);
    }
    return registry;
  }

  private static void createNode(
      WorkflowSessionRegistry registry,
      OperationActor actor,
      String operationId,
      String nodeId,
      String label,
      long expectedRevision) {
    registry.applyOperation(
        "session.undo",
        registry.inspect("session.undo").mode(),
        actor,
        expectedRevision,
        new WorkflowOperation.CreateNode(
            operationId,
            BASE_TIME.plusSeconds(expectedRevision),
            actor.actorId(),
            new Node(nodeId, "test", label, List.of(), List.of(), Metadata.empty())));
  }

  private static void renameNode(
      WorkflowSessionRegistry registry,
      OperationActor actor,
      String operationId,
      String nodeId,
      String previousLabel,
      String newLabel,
      long expectedRevision) {
    registry.applyOperation(
        "session.undo",
        registry.inspect("session.undo").mode(),
        actor,
        expectedRevision,
        new WorkflowOperation.RenameNode(
            operationId,
            BASE_TIME.plusSeconds(expectedRevision),
            actor.actorId(),
            nodeId,
            previousLabel,
            newLabel));
  }

  private static Node node(WorkflowSessionRegistry registry, String nodeId) {
    return registry.workflow("session.undo").nodes().stream()
        .filter(candidate -> candidate.id().equals(nodeId))
        .findFirst()
        .orElseThrow();
  }

  private static List<String> blockerIds(WorkflowUndoPreview preview) {
    return preview.blockingOperations().stream()
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
