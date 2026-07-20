package org.hammer.audio.infrastructure.workflow.history;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.history.WorkflowHistoryAccessException;
import org.junit.jupiter.api.Test;

class CollaborationWorkflowHistoryAccessPolicyTest {

  @Test
  void blocksJoinedParticipantsButAllowsRestoreAfterEveryoneLeaves() {
    WorkflowSessionRegistry registry = new WorkflowSessionRegistry();
    OperationActor owner = OperationActor.forAuthor("owner");
    registry.create(
        "session.restore",
        CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
        owner,
        new Workflow("workflow.restore", "Restore workflow", List.of(), List.of()));
    CollaborationWorkflowHistoryAccessPolicy policy =
        new CollaborationWorkflowHistoryAccessPolicy(registry);

    assertThrows(
        WorkflowHistoryAccessException.class,
        () -> policy.assertRestoreAllowed("main", "workflow.restore"));

    registry.leave("session.restore", owner.actorId());
    assertDoesNotThrow(() -> policy.assertRestoreAllowed("main", "workflow.restore"));
  }
}
