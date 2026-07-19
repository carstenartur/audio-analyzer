package org.hammer.audio.workflow.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionRevisionConflictException;
import org.junit.jupiter.api.Test;

class WorkflowHistoryCapabilityRevisionTest {

  private static final String SESSION_ID = "session.capability.revision";
  private static final OperationActor OWNER =
      new OperationActor("actor.owner", "user.owner", "Owner");
  private static final Instant BASE_TIME = Instant.parse("2026-07-19T09:30:00Z");

  @Test
  void capabilityRevisionCannotAuthorizeCommandAfterSessionAdvances() {
    WorkflowSessionRegistry registry = new WorkflowSessionRegistry();
    registry.create(
        SESSION_ID,
        CollaborationMode.PRIVATE_WORKSPACE,
        OWNER,
        new Workflow("workflow.capability", "Capability revision", List.of(), List.of()));
    registry.applyOperation(
        SESSION_ID,
        CollaborationMode.PRIVATE_WORKSPACE,
        OWNER,
        0,
        createNode("operation.first", "node.first", 0));
    WorkflowHistoryCapabilities capabilities = registry.capabilities(SESSION_ID, OWNER);

    registry.applyOperation(
        SESSION_ID,
        CollaborationMode.PRIVATE_WORKSPACE,
        OWNER,
        1,
        createNode("operation.second", "node.second", 1));

    assertEquals(1, capabilities.revision());
    assertThrows(
        WorkflowSessionRevisionConflictException.class,
        () ->
            registry.undo(
                SESSION_ID,
                new UndoWorkflowCommand(
                    "command.from-stale-capability",
                    OWNER,
                    capabilities.revision(),
                    capabilities.personalUndo().operation().operationId(),
                    null)));
    assertEquals(2, registry.inspect(SESSION_ID).revision());
    assertEquals(2, registry.inspect(SESSION_ID).operationCount());
  }

  private static WorkflowOperation.CreateNode createNode(
      String operationId, String nodeId, long secondOffset) {
    return new WorkflowOperation.CreateNode(
        operationId,
        BASE_TIME.plusSeconds(secondOffset),
        OWNER.actorId(),
        new Node(nodeId, "test", nodeId, List.of(), List.of(), Metadata.empty()));
  }
}
