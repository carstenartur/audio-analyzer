package org.hammer.audio.workflow.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.collaboration.WorkflowHistoryCapabilities.ActionStatus;
import org.hammer.audio.workflow.collaboration.WorkflowSessionException.Code;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowOperation;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowOutboxEntry;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowSession;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationCommandMetadata;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationPersistenceCodec;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionAppendCommand;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionAppendResult;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionStateStore;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
import org.junit.jupiter.api.Test;

class WorkflowSessionLegacyHistoryTest {

  private static final String SESSION_ID = "session.legacy.history";
  private static final OperationActor OWNER =
      new OperationActor("actor.owner", "user.owner", "Owner");
  private static final Instant OCCURRED_AT = Instant.parse("2026-07-19T09:00:00Z");

  @Test
  void legacyOperationRemainsVisibleButCannotBeOfferedAsExecutableUndo() {
    WorkflowOperation.CreateNode operation = legacyCreateOperation();
    Workflow workflow =
        new Workflow("workflow.legacy", "Legacy", List.of(operation.node()), List.of());
    LegacyStore store = new LegacyStore(workflow, legacyStoredOperation(operation));
    WorkflowSessionRegistry registry =
        new WorkflowSessionRegistry(new WorkflowSessionEventHub(16, 4), store);
    registry.join(SESSION_ID, OWNER);

    WorkflowHistoryPage history = registry.history(SESSION_ID, OWNER, null, 10);
    WorkflowHistoryDescriptor descriptor = history.operations().getFirst();
    WorkflowHistoryCapabilities capabilities = registry.capabilities(SESSION_ID, OWNER);

    assertEquals(List.of("node.legacy"), descriptor.affectedObjectIds());
    assertFalse(descriptor.reconstructible());
    assertTrue(descriptor.activeUndoTarget());
    assertEquals(ActionStatus.NOT_RECONSTRUCTIBLE, capabilities.personalUndo().status());
    assertEquals(descriptor, capabilities.personalUndo().operation());
    assertTrue(capabilities.personalUndo().blockingOperations().isEmpty());
    assertEquals(1, store.operations(SESSION_ID).size());

    WorkflowSessionException failure =
        assertThrows(
            WorkflowSessionException.class,
            () -> registry.previewUndo(SESSION_ID, OWNER, "operation.legacy"));
    assertEquals(Code.OPERATION_NOT_UNDOABLE, failure.code());
    assertEquals(1, store.operations(SESSION_ID).size());
  }

  private static WorkflowOperation.CreateNode legacyCreateOperation() {
    return new WorkflowOperation.CreateNode(
        "operation.legacy",
        OCCURRED_AT,
        OWNER.actorId(),
        new Node(
            "node.legacy",
            "recording-input",
            "Legacy node",
            List.of(),
            List.of(),
            Metadata.empty()));
  }

  private static StoredWorkflowOperation legacyStoredOperation(
      WorkflowOperation.CreateNode operation) {
    return new StoredWorkflowOperation(
        SESSION_ID,
        operation.operationId(),
        operation.author(),
        operation.getClass().getSimpleName(),
        operation.timestamp(),
        1,
        1,
        WorkflowOperationPersistenceCodec.encode(operation).payload(),
        0,
        null,
        WorkflowOperationCommandMetadata.normal(operation.operationId()));
  }

  private static final class LegacyStore implements WorkflowSessionStateStore {
    private StoredWorkflowSession session;
    private final List<StoredWorkflowOperation> operations;

    LegacyStore(Workflow workflow, StoredWorkflowOperation operation) {
      this.session =
          new StoredWorkflowSession(
              SESSION_ID,
              CollaborationMode.PRIVATE_WORKSPACE,
              OWNER,
              OCCURRED_AT.minusSeconds(1),
              workflow.id(),
              new WorkflowDslSerializer().serialize(workflow),
              1,
              1,
              false);
      this.operations = List.of(operation);
    }

    @Override
    public StoredWorkflowSession create(StoredWorkflowSession created) {
      throw new UnsupportedOperationException("recovery-only store");
    }

    @Override
    public Optional<StoredWorkflowSession> find(String sessionId) {
      return SESSION_ID.equals(sessionId) ? Optional.of(session) : Optional.empty();
    }

    @Override
    public List<StoredWorkflowSession> openSessions() {
      return List.of(session);
    }

    @Override
    public WorkflowSessionAppendResult append(WorkflowSessionAppendCommand command) {
      throw new UnsupportedOperationException("recovery-only store");
    }

    @Override
    public StoredWorkflowSession advanceEventSequence(String sessionId, long expectedSequence) {
      if (!SESSION_ID.equals(sessionId) || session.sequence() != expectedSequence) {
        throw new IllegalStateException("unexpected sequence reservation");
      }
      session =
          new StoredWorkflowSession(
              session.sessionId(),
              session.mode(),
              session.owner(),
              session.createdAt(),
              session.workflowId(),
              session.workflowDsl(),
              session.revision(),
              Math.addExact(expectedSequence, 1),
              false);
      return session;
    }

    @Override
    public StoredWorkflowSession close(
        String sessionId, long expectedRevision, long expectedSequence) {
      throw new UnsupportedOperationException("recovery-only store");
    }

    @Override
    public List<StoredWorkflowOperation> operations(String sessionId) {
      return SESSION_ID.equals(sessionId) ? operations : List.of();
    }

    @Override
    public List<StoredWorkflowOutboxEntry> pendingOutbox(int limit) {
      return List.of();
    }
  }
}
