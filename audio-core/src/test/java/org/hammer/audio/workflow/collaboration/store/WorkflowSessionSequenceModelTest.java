package org.hammer.audio.workflow.collaboration.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.junit.jupiter.api.Test;

class WorkflowSessionSequenceModelTest {

  private static final OperationActor OWNER = new OperationActor("actor", "user", "Owner");

  @Test
  void eventSequenceConflictExposesMachineReadableValues() {
    WorkflowSessionSequenceConflictException conflict =
        new WorkflowSessionSequenceConflictException("session.test", 4, 6);

    assertEquals("session.test", conflict.sessionId());
    assertEquals(4, conflict.expectedSequence());
    assertEquals(6, conflict.actualSequence());
    assertTrue(conflict.getMessage().contains("session.test"));
  }

  @Test
  void semanticRevisionCannotExceedCollaborationEventSequence() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StoredWorkflowSession(
                "session.test",
                CollaborationMode.PRIVATE_WORKSPACE,
                OWNER,
                Instant.parse("2026-07-18T00:00:00Z"),
                "workflow.test",
                "workflow\n  id: workflow.test\n  name: Test\n  nodes:\n  edges:\n",
                3,
                2,
                false));
  }
}
