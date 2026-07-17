package org.hammer.audio.workflow.collaboration.store;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.junit.jupiter.api.Test;

class WorkflowSessionPersistenceModelTest {

  private static final Instant OCCURRED_AT = Instant.parse("2026-07-17T00:00:00Z");
  private static final OperationActor OWNER = new OperationActor("actor", "user", "Owner");

  @Test
  void durableRecordsExposeCanonicalState() {
    StoredWorkflowSession session = session();
    WorkflowOperationPersistenceData operationData = operationData();
    WorkflowOutboxEventData eventData = eventData();
    WorkflowSessionAppendCommand command =
        new WorkflowSessionAppendCommand(
            session.sessionId(),
            session.revision(),
            operationData,
            session.workflowId(),
            "workflow workflow.test { node input }",
            eventData);
    StoredWorkflowOperation operation =
        new StoredWorkflowOperation(
            session.sessionId(),
            operationData.operationId(),
            operationData.actorId(),
            operationData.operationType(),
            operationData.occurredAt(),
            1,
            1,
            operationData.payload());
    StoredWorkflowOutboxEntry outbox = storedOutbox(null);
    WorkflowSessionAppendResult result =
        new WorkflowSessionAppendResult(session, operation, outbox, false);

    assertAll(
        () -> assertEquals("session.test", command.sessionId()),
        () -> assertEquals(operationData, command.operation()),
        () -> assertEquals(eventData, command.outboxEvent()),
        () -> assertEquals(session, result.session()),
        () -> assertEquals(operation, result.operation()),
        () -> assertEquals(outbox, result.outboxEntry()),
        () -> assertFalse(result.duplicate()),
        () -> assertTrue(outbox.pending()));
  }

  @Test
  void outboxPublicationStateIsExplicit() {
    StoredWorkflowOutboxEntry published = storedOutbox(OCCURRED_AT.plusSeconds(5));

    assertFalse(published.pending());
    assertEquals(OCCURRED_AT.plusSeconds(5), published.publishedAt());
  }

  @Test
  void invalidDurableStateIsRejected() {
    assertAll(
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    new StoredWorkflowSession(
                        " ",
                        CollaborationMode.PRIVATE_WORKSPACE,
                        OWNER,
                        OCCURRED_AT,
                        "workflow.test",
                        "dsl",
                        0,
                        0,
                        false)),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    new StoredWorkflowSession(
                        "session.test",
                        CollaborationMode.PRIVATE_WORKSPACE,
                        OWNER,
                        OCCURRED_AT,
                        "workflow.test",
                        "dsl",
                        -1,
                        0,
                        false)),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    new WorkflowOperationPersistenceData(
                        "operation", "actor", " ", OCCURRED_AT, "payload")),
        () ->
            assertThrows(
                NullPointerException.class,
                () -> new WorkflowOutboxEventData("event", "TYPE", null, "payload")),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    new WorkflowSessionAppendCommand(
                        "session.test", -1, operationData(), "workflow.test", "dsl", eventData())),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    new StoredWorkflowOperation(
                        "session.test",
                        "operation",
                        "actor",
                        "TYPE",
                        OCCURRED_AT,
                        0,
                        1,
                        "payload")),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    new StoredWorkflowOutboxEntry(
                        "event",
                        "session.test",
                        1,
                        1,
                        "TYPE",
                        OCCURRED_AT,
                        "payload",
                        -1,
                        OCCURRED_AT,
                        null)),
        () ->
            assertThrows(
                NullPointerException.class,
                () -> new WorkflowSessionAppendResult(null, null, null, false)));
  }

  @Test
  void typedConflictsExposeMachineReadableValues() {
    WorkflowSessionRevisionConflictException revisionConflict =
        new WorkflowSessionRevisionConflictException("session.test", 3, 4);
    WorkflowOperationPersistenceConflictException operationConflict =
        new WorkflowOperationPersistenceConflictException("session.test", "operation.test");

    assertAll(
        () -> assertEquals("session.test", revisionConflict.sessionId()),
        () -> assertEquals(3, revisionConflict.expectedRevision()),
        () -> assertEquals(4, revisionConflict.actualRevision()),
        () -> assertTrue(revisionConflict.getMessage().contains("session.test")),
        () -> assertEquals("session.test", operationConflict.sessionId()),
        () -> assertEquals("operation.test", operationConflict.operationId()),
        () -> assertTrue(operationConflict.getMessage().contains("operation.test")));
  }

  private static StoredWorkflowSession session() {
    return new StoredWorkflowSession(
        "session.test",
        CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
        OWNER,
        OCCURRED_AT,
        "workflow.test",
        "workflow workflow.test { }",
        0,
        0,
        false);
  }

  private static WorkflowOperationPersistenceData operationData() {
    return new WorkflowOperationPersistenceData(
        "operation.test", "actor", "CreateNode", OCCURRED_AT, "{\"nodeId\":\"input\"}");
  }

  private static WorkflowOutboxEventData eventData() {
    return new WorkflowOutboxEventData(
        "event.test", "WORKFLOW_OPERATION_ACCEPTED", OCCURRED_AT, "{\"revision\":1}");
  }

  private static StoredWorkflowOutboxEntry storedOutbox(Instant publishedAt) {
    return new StoredWorkflowOutboxEntry(
        "event.test",
        "session.test",
        1,
        1,
        "WORKFLOW_OPERATION_ACCEPTED",
        OCCURRED_AT,
        "{\"revision\":1}",
        0,
        OCCURRED_AT,
        publishedAt);
  }
}
