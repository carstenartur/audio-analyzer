package org.hammer.audio.workflow.collaboration;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowOperation;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowSession;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationBodyCodec;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationPersistenceCodec;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationPersistenceData;
import org.hammer.audio.workflow.collaboration.store.WorkflowOutboxEventData;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionAppendCommand;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionAppendResult;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionRevisionConflictException;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionStateStore;
import org.hammer.audio.workflow.dsl.WorkflowDslParser;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;

/**
 * Coordinates optional durable persistence without leaking storage details into session runtime.
 */
final class WorkflowSessionPersistenceCoordinator {

  private static final String OPERATION_EVENT_TYPE = "WORKFLOW_OPERATION_ACCEPTED";

  private final WorkflowSessionStateStore stateStore;
  private final WorkflowDslParser parser = new WorkflowDslParser();
  private final WorkflowDslSerializer serializer = new WorkflowDslSerializer();

  private WorkflowSessionPersistenceCoordinator(WorkflowSessionStateStore stateStore) {
    this.stateStore = stateStore;
  }

  static WorkflowSessionPersistenceCoordinator inMemory() {
    return new WorkflowSessionPersistenceCoordinator(null);
  }

  static WorkflowSessionPersistenceCoordinator durable(WorkflowSessionStateStore stateStore) {
    return new WorkflowSessionPersistenceCoordinator(
        Objects.requireNonNull(stateStore, "stateStore"));
  }

  boolean durable() {
    return stateStore != null;
  }

  List<RecoveredSession> recoverOpenSessions() {
    if (!durable()) {
      return List.of();
    }
    return stateStore.openSessions().stream().map(this::recover).toList();
  }

  void create(
      String sessionId,
      CollaborationMode mode,
      OperationActor owner,
      Instant createdAt,
      Workflow workflow,
      long initialSequence) {
    if (!durable()) {
      return;
    }
    stateStore.create(
        new StoredWorkflowSession(
            sessionId,
            mode,
            owner,
            createdAt,
            workflow.id(),
            serializer.serialize(workflow),
            0,
            initialSequence,
            false));
  }

  void compensateFailedCreate(
      String sessionId, long expectedRevision, long expectedSequence, RuntimeException failure) {
    if (!durable()) {
      return;
    }
    try {
      stateStore.close(sessionId, expectedRevision, expectedSequence);
    } catch (RuntimeException cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
    }
  }

  OperationIdentity identity(WorkflowOperation operation) {
    return OperationIdentity.from(WorkflowOperationPersistenceCodec.encode(operation));
  }

  void requireExpectedRevision(String sessionId, long expectedRevision, long actualRevision) {
    if (expectedRevision != actualRevision) {
      throw new WorkflowSessionRevisionConflictException(
          sessionId, expectedRevision, actualRevision);
    }
  }

  AppendOutcome append(
      String sessionId,
      long expectedRevision,
      long expectedSequence,
      WorkflowOperation operation,
      Workflow updatedWorkflow) {
    WorkflowOperationPersistenceData persistenceData =
        WorkflowOperationPersistenceCodec.encode(operation);
    OperationIdentity candidate = OperationIdentity.from(persistenceData);
    long nextRevision = Math.addExact(expectedRevision, 1);
    long nextSequence = Math.addExact(expectedSequence, 1);
    if (!durable()) {
      return new AppendOutcome(updatedWorkflow, candidate, nextRevision, nextSequence, false);
    }

    WorkflowSessionAppendResult result =
        stateStore.append(
            new WorkflowSessionAppendCommand(
                sessionId,
                expectedRevision,
                persistenceData,
                updatedWorkflow.id(),
                serializer.serialize(updatedWorkflow),
                new WorkflowOutboxEventData(
                    sessionId + ":" + nextSequence,
                    OPERATION_EVENT_TYPE,
                    operation.timestamp(),
                    persistenceData.payload())));
    validateAppendResult(sessionId, expectedSequence, result);
    Workflow durableWorkflow = result.duplicate() ? parse(result.session()) : updatedWorkflow;
    return new AppendOutcome(
        durableWorkflow,
        OperationIdentity.from(result.operation()),
        result.session().revision(),
        result.session().sequence(),
        result.duplicate());
  }

  long advanceEventSequence(String sessionId, long currentRevision, long currentSequence) {
    long nextSequence = Math.addExact(currentSequence, 1);
    if (!durable()) {
      return nextSequence;
    }
    StoredWorkflowSession advanced = stateStore.advanceEventSequence(sessionId, currentSequence);
    validateLifecycleState(sessionId, advanced, currentRevision, nextSequence, false);
    return advanced.sequence();
  }

  long close(String sessionId, long currentRevision, long currentSequence) {
    long nextSequence = Math.addExact(currentSequence, 1);
    if (!durable()) {
      return nextSequence;
    }
    StoredWorkflowSession closed = stateStore.close(sessionId, currentRevision, currentSequence);
    validateLifecycleState(sessionId, closed, currentRevision, nextSequence, true);
    return closed.sequence();
  }

  private RecoveredSession recover(StoredWorkflowSession storedSession) {
    String sessionId = storedSession.sessionId();
    try {
      Workflow workflow = parse(storedSession);
      List<StoredWorkflowOperation> operations = stateStore.operations(sessionId);
      validateRecoveredHistory(storedSession, operations);
      List<OperationIdentity> identities =
          operations.stream().map(OperationIdentity::from).toList();
      long distinctIds = identities.stream().map(OperationIdentity::operationId).distinct().count();
      if (distinctIds != identities.size()) {
        throw new WorkflowSessionRecoveryException(
            sessionId, "Duplicate durable operation id in session " + sessionId);
      }
      return new RecoveredSession(
          sessionId,
          storedSession.mode(),
          storedSession.owner(),
          storedSession.createdAt(),
          workflow,
          identities,
          storedSession.revision(),
          storedSession.sequence());
    } catch (WorkflowSessionRecoveryException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new WorkflowSessionRecoveryException(
          sessionId, "Failed to recover durable collaboration session " + sessionId, failure);
    }
  }

  private Workflow parse(StoredWorkflowSession session) {
    Workflow workflow = parser.parse(session.workflowDsl());
    if (!workflow.id().equals(session.workflowId())) {
      throw new WorkflowSessionRecoveryException(
          session.sessionId(),
          "Recovered workflow id '"
              + workflow.id()
              + "' does not match durable id '"
              + session.workflowId()
              + "'");
    }
    return workflow;
  }

  private static void validateRecoveredHistory(
      StoredWorkflowSession session, List<StoredWorkflowOperation> operations) {
    long expectedRevision = 1;
    long previousSequence = 0;
    for (StoredWorkflowOperation operation : operations) {
      if (!operation.sessionId().equals(session.sessionId())) {
        throw recoveryFailure(
            session,
            "Durable operation belongs to a different session: " + operation.operationId());
      }
      if (operation.revision() != expectedRevision) {
        throw recoveryFailure(
            session,
            "Durable semantic revisions are not contiguous at operation "
                + operation.operationId());
      }
      if (operation.sequence() <= previousSequence || operation.sequence() > session.sequence()) {
        throw recoveryFailure(
            session, "Durable event sequence is invalid at operation " + operation.operationId());
      }
      expectedRevision++;
      previousSequence = operation.sequence();
    }
    if (session.revision() != operations.size()) {
      throw recoveryFailure(session, "Durable aggregate revision does not match operation history");
    }
    if (session.sequence() < previousSequence || session.sequence() < session.revision()) {
      throw recoveryFailure(session, "Durable aggregate event sequence precedes recovered history");
    }
  }

  private static void validateAppendResult(
      String sessionId, long expectedSequence, WorkflowSessionAppendResult result) {
    StoredWorkflowSession session = result.session();
    long nextSequence = Math.addExact(expectedSequence, 1);
    if (!session.sessionId().equals(sessionId)
        || session.closed()
        || session.revision() != result.operation().revision()
        || session.sequence() != nextSequence
        || result.operation().sequence() != nextSequence
        || !result.operation().hasOperationBody()
        || !result.outboxEntry().sessionId().equals(sessionId)
        || result.outboxEntry().sequence() != nextSequence) {
      throw new IllegalStateException(
          "Durable append result is inconsistent for session " + sessionId);
    }
  }

  private static void validateLifecycleState(
      String sessionId,
      StoredWorkflowSession state,
      long expectedRevision,
      long expectedSequence,
      boolean expectedClosed) {
    if (!state.sessionId().equals(sessionId)
        || state.revision() != expectedRevision
        || state.sequence() != expectedSequence
        || state.closed() != expectedClosed) {
      throw new IllegalStateException(
          "Durable lifecycle result is inconsistent for session " + sessionId);
    }
  }

  private static WorkflowSessionRecoveryException recoveryFailure(
      StoredWorkflowSession session, String message) {
    return new WorkflowSessionRecoveryException(session.sessionId(), message);
  }

  record OperationIdentity(
      String operationId,
      String operationType,
      String actorId,
      String payload,
      int bodyVersion,
      String operationBody) {

    OperationIdentity {
      Objects.requireNonNull(operationId, "operationId");
      Objects.requireNonNull(operationType, "operationType");
      Objects.requireNonNull(actorId, "actorId");
      Objects.requireNonNull(payload, "payload");
      if (bodyVersion < 0) {
        throw new IllegalArgumentException("bodyVersion must be >= 0");
      }
      if (bodyVersion == 0) {
        operationBody = null;
      } else {
        Objects.requireNonNull(operationBody, "operationBody");
        WorkflowOperationBodyCodec.decode(bodyVersion, operationBody);
      }
    }

    static OperationIdentity from(WorkflowOperationPersistenceData operation) {
      return new OperationIdentity(
          operation.operationId(),
          operation.operationType(),
          operation.actorId(),
          operation.payload(),
          operation.bodyVersion(),
          operation.operationBody());
    }

    static OperationIdentity from(StoredWorkflowOperation operation) {
      return new OperationIdentity(
          operation.operationId(),
          operation.operationType(),
          operation.actorId(),
          operation.payload(),
          operation.bodyVersion(),
          operation.operationBody());
    }

    boolean hasOperationBody() {
      return bodyVersion > 0;
    }

    Optional<WorkflowOperation> operation() {
      return hasOperationBody()
          ? Optional.of(WorkflowOperationBodyCodec.decode(bodyVersion, operationBody))
          : Optional.empty();
    }

    boolean matchesRetry(OperationIdentity candidate) {
      Objects.requireNonNull(candidate, "candidate");
      if (!operationId.equals(candidate.operationId)
          || !operationType.equals(candidate.operationType)
          || !actorId.equals(candidate.actorId)
          || !payload.equals(candidate.payload)) {
        return false;
      }
      if (!hasOperationBody() || !candidate.hasOperationBody()) {
        return true;
      }
      WorkflowOperation storedOperation = operation().orElseThrow();
      WorkflowOperation candidateOperation = candidate.operation().orElseThrow();
      WorkflowOperation normalizedCandidate =
          WorkflowOperationBodyCodec.reidentify(
              candidateOperation,
              candidateOperation.operationId(),
              storedOperation.timestamp(),
              candidateOperation.author());
      WorkflowOperationBodyCodec.EncodedBody normalizedBody =
          WorkflowOperationBodyCodec.encode(normalizedCandidate);
      return bodyVersion == normalizedBody.version() && operationBody.equals(normalizedBody.body());
    }
  }

  record RecoveredSession(
      String sessionId,
      CollaborationMode mode,
      OperationActor owner,
      Instant createdAt,
      Workflow workflow,
      List<OperationIdentity> operations,
      long revision,
      long sequence) {

    RecoveredSession {
      Objects.requireNonNull(sessionId, "sessionId");
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(createdAt, "createdAt");
      Objects.requireNonNull(workflow, "workflow");
      operations = List.copyOf(Objects.requireNonNull(operations, "operations"));
    }
  }

  record AppendOutcome(
      Workflow workflow,
      OperationIdentity identity,
      long revision,
      long sequence,
      boolean duplicate) {

    AppendOutcome {
      Objects.requireNonNull(workflow, "workflow");
      Objects.requireNonNull(identity, "identity");
    }
  }
}
