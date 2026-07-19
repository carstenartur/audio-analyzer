package org.hammer.audio.workflow.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.collaboration.WorkflowHistoryCapabilities.ActionStatus;
import org.hammer.audio.workflow.collaboration.WorkflowHistoryDescriptor.CommandKind;
import org.junit.jupiter.api.Test;

class WorkflowSessionHistoryCapabilitiesTest {

  private static final String SESSION_ID = "session.history";
  private static final OperationActor OWNER =
      new OperationActor("actor.owner", "user.owner", "Owner");
  private static final OperationActor GUEST =
      new OperationActor("actor.guest", "user.guest", "Guest");
  private static final Instant BASE_TIME = Instant.parse("2026-07-19T08:00:00Z");

  @Test
  void historyPagesAreStableNewestFirstAndReadOnly() {
    WorkflowSessionRegistry registry = registry(CollaborationMode.PRIVATE_WORKSPACE);
    createNode(registry, OWNER, "operation.one", "node.one", 0);
    createNode(registry, OWNER, "operation.two", "node.two", 1);
    createNode(registry, OWNER, "operation.three", "node.three", 2);
    WorkflowSessionRegistry.SessionSnapshot before = registry.inspect(SESSION_ID);

    WorkflowHistoryPage newest = registry.history(SESSION_ID, OWNER, null, 2);

    assertEquals(3, newest.currentRevision());
    assertEquals(List.of("operation.three", "operation.two"), operationIds(newest));
    assertEquals(2L, newest.nextBeforeRevision());
    assertEquals(BASE_TIME.plusSeconds(2), newest.operations().getFirst().occurredAt());
    assertEquals(3, newest.operations().getFirst().revision());
    assertEquals(5, newest.operations().getFirst().sequence());

    WorkflowHistoryPage older = registry.history(SESSION_ID, OWNER, newest.nextBeforeRevision(), 2);
    assertEquals(List.of("operation.one"), operationIds(older));
    assertNull(older.nextBeforeRevision());
    assertEquals(before, registry.inspect(SESSION_ID));
  }

  @Test
  void capabilitiesMoveFromPersonalUndoToRedoAndBackToUndo() {
    WorkflowSessionRegistry registry = registry(CollaborationMode.PRIVATE_WORKSPACE);
    createNode(registry, OWNER, "operation.create", "node.one", 0);

    WorkflowHistoryCapabilities beforeUndo = registry.capabilities(SESSION_ID, OWNER);
    assertTrue(beforeUndo.personalUndoPermitted());
    assertEquals(ActionStatus.AVAILABLE, beforeUndo.personalUndo().status());
    assertEquals("operation.create", beforeUndo.personalUndo().operation().operationId());
    assertNull(beforeUndo.redo());

    WorkflowHistoryCommandResult undone =
        registry.undo(SESSION_ID, new UndoWorkflowCommand("command.undo", OWNER, 1, null, null));
    WorkflowHistoryCapabilities afterUndo = registry.capabilities(SESSION_ID, OWNER);
    assertNull(afterUndo.personalUndo());
    assertEquals(ActionStatus.AVAILABLE, afterUndo.redo().status());
    assertEquals(undone.operationId(), afterUndo.redo().operation().operationId());
    assertEquals(CommandKind.UNDO, afterUndo.redo().operation().commandKind());

    WorkflowRedoPreview preview = registry.previewRedo(SESSION_ID, OWNER, undone.operationId());
    assertTrue(preview.safe());
    assertEquals(afterUndo.redo().operation().occurredAt(), preview.targetOccurredAt());

    registry.redo(
        SESSION_ID, new RedoWorkflowCommand("command.redo", OWNER, 2, undone.operationId()));
    WorkflowHistoryCapabilities afterRedo = registry.capabilities(SESSION_ID, OWNER);
    assertNull(afterRedo.redo());
    assertEquals(ActionStatus.AVAILABLE, afterRedo.personalUndo().status());
    assertEquals(CommandKind.REDO, afterRedo.personalUndo().operation().commandKind());
  }

  @Test
  void capabilitiesExposeBlockersAndImmutableModePermissions() {
    WorkflowSessionRegistry personal = registry(CollaborationMode.SHARED_SESSION_PERSONAL_UNDO);
    createNode(personal, OWNER, "operation.owner.create", "node.shared", 0);
    renameNode(
        personal, GUEST, "operation.guest.rename", "node.shared", "node.shared", "Guest rename", 1);

    WorkflowHistoryCapabilities blocked = personal.capabilities(SESSION_ID, OWNER);
    assertEquals(ActionStatus.BLOCKED, blocked.personalUndo().status());
    assertFalse(blocked.personalUndo().available());
    assertEquals(
        List.of("operation.guest.rename"),
        blocked.personalUndo().blockingOperations().stream()
            .map(WorkflowUndoPreview.BlockingOperation::operationId)
            .toList());
    assertFalse(blocked.sharedUndoPermitted());

    WorkflowSessionRegistry shared = registry(CollaborationMode.SHARED_SESSION_SHARED_UNDO);
    createNode(shared, OWNER, "operation.shared", "node.shared", 0);
    WorkflowHistoryCapabilities sharedCapabilities = shared.capabilities(SESSION_ID, OWNER);
    assertFalse(sharedCapabilities.personalUndoPermitted());
    assertNull(sharedCapabilities.personalUndo());
    assertTrue(sharedCapabilities.sharedUndoPermitted());
  }

  private static WorkflowSessionRegistry registry(CollaborationMode mode) {
    WorkflowSessionRegistry registry = new WorkflowSessionRegistry();
    registry.create(SESSION_ID, mode, OWNER, emptyWorkflow());
    if (mode != CollaborationMode.PRIVATE_WORKSPACE) {
      registry.join(SESSION_ID, GUEST);
    }
    return registry;
  }

  private static void createNode(
      WorkflowSessionRegistry registry,
      OperationActor actor,
      String operationId,
      String nodeId,
      long expectedRevision) {
    registry.applyOperation(
        SESSION_ID,
        registry.inspect(SESSION_ID).mode(),
        actor,
        expectedRevision,
        new WorkflowOperation.CreateNode(
            operationId,
            BASE_TIME.plusSeconds(expectedRevision),
            actor.actorId(),
            new Node(nodeId, "test", nodeId, List.of(), List.of(), Metadata.empty())));
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
        SESSION_ID,
        registry.inspect(SESSION_ID).mode(),
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

  private static List<String> operationIds(WorkflowHistoryPage page) {
    return page.operations().stream().map(WorkflowHistoryDescriptor::operationId).toList();
  }

  private static Workflow emptyWorkflow() {
    return new Workflow("workflow.history", "History workflow", List.of(), List.of());
  }
}
