package org.hammer.audio.workflow.collaboration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.WorkflowOperationLog;
import org.hammer.audio.workflow.collaboration.WorkflowSessionException.Code;
import org.hammer.audio.workflow.collaboration.WorkflowSessionPersistenceCoordinator.AppendOutcome;
import org.hammer.audio.workflow.collaboration.WorkflowSessionPersistenceCoordinator.LifecycleState;
import org.hammer.audio.workflow.collaboration.WorkflowSessionPersistenceCoordinator.OperationIdentity;
import org.hammer.audio.workflow.collaboration.WorkflowSessionPersistenceCoordinator.RecoveredSession;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionRevisionConflictException;

/** Owns the synchronized runtime state for one collaboration session. */
final class WorkflowSessionEntry {

  private final String sessionId;
  private final CollaborationMode mode;
  private final OperationActor owner;
  private final Instant createdAt;
  private final WorkflowOperationLog operationLog;
  private final WorkflowSessionEventHub eventHub;
  private final WorkflowSessionPersistenceCoordinator persistence;
  private final Map<String, OperationActor> participants = new ConcurrentHashMap<>();
  private final Map<String, PresenceState> presenceByActor = new ConcurrentHashMap<>();
  private final Map<String, OperationIdentity> operationsById = new ConcurrentHashMap<>();
  private final ReentrantLock lock = new ReentrantLock();
  private int operationCount;
  private long revision;
  private long sequence;
  private volatile boolean closed;

  private WorkflowSessionEntry(
      SessionDefinition definition, RecoveryState recovery, Services services) {
    this.sessionId = definition.sessionId();
    this.mode = definition.mode();
    this.owner = definition.owner();
    this.createdAt = definition.createdAt();
    this.operationLog = new WorkflowOperationLog(definition.workflow());
    this.eventHub = services.eventHub();
    this.persistence = services.persistence();
    this.revision = recovery.revision();
    this.sequence = recovery.sequence();
    for (OperationIdentity operation : recovery.operations()) {
      OperationIdentity previous = operationsById.putIfAbsent(operation.operationId(), operation);
      if (previous != null) {
        throw new WorkflowSessionRecoveryException(
            sessionId, "Duplicate durable operation id: " + operation.operationId());
      }
    }
    this.operationCount = recovery.operations().size();
    if (recovery.ownerConnected()) {
      participants.put(owner.actorId(), owner);
    }
  }

  static WorkflowSessionEntry created(
      String sessionId,
      CollaborationMode mode,
      OperationActor owner,
      Instant createdAt,
      Workflow workflow,
      WorkflowSessionEventHub eventHub,
      WorkflowSessionPersistenceCoordinator persistence) {
    SessionDefinition definition =
        new SessionDefinition(sessionId, mode, owner, createdAt, workflow);
    RecoveryState recovery = new RecoveryState(List.of(), true, 0, 2);
    return new WorkflowSessionEntry(definition, recovery, new Services(eventHub, persistence));
  }

  static WorkflowSessionEntry recovered(
      RecoveredSession recovered,
      WorkflowSessionEventHub eventHub,
      WorkflowSessionPersistenceCoordinator persistence) {
    SessionDefinition definition =
        new SessionDefinition(
            recovered.sessionId(),
            recovered.mode(),
            recovered.owner(),
            recovered.createdAt(),
            recovered.workflow());
    RecoveryState recovery =
        new RecoveryState(
            recovered.operations(), false, recovered.revision(), recovered.sequence());
    return new WorkflowSessionEntry(definition, recovery, new Services(eventHub, persistence));
  }

  void verifyInitialEventStream(long expectedSequence) {
    lock.lock();
    try {
      verifyEventHubState(expectedSequence, 0, "session creation");
    } finally {
      lock.unlock();
    }
  }

  WorkflowSessionRegistry.SessionSnapshot join(OperationActor actor) {
    lock.lock();
    try {
      requireOpen();
      if (mode == CollaborationMode.PRIVATE_WORKSPACE
          && !owner.actorId().equals(actor.actorId())) {
        throw error(
            Code.PRIVATE_WORKSPACE_ACCESS_DENIED,
            "Private workspace can only be joined by its owner: " + owner.actorId());
      }
      OperationActor existing = participants.get(actor.actorId());
      if (existing != null && !existing.equals(actor)) {
        throw error(
            Code.ACTOR_METADATA_MISMATCH,
            "Actor metadata mismatch for already joined actor: " + actor.actorId());
      }
      if (existing == null) {
        long nextSequence = reserveNonSemanticEvent();
        participants.put(actor.actorId(), actor);
        eventHub.actorJoined(sessionId, actor);
        confirmNonSemanticEvent(nextSequence, "actor join");
      }
      return snapshotLocked();
    } finally {
      lock.unlock();
    }
  }

  WorkflowSessionRegistry.SessionSnapshot leave(String actorId) {
    lock.lock();
    try {
      requireOpen();
      OperationActor actor = participants.get(actorId);
      if (actor == null) {
        throw error(Code.ACTOR_NOT_JOINED, "Actor is not joined: " + actorId);
      }
      long nextSequence = reserveNonSemanticEvent();
      participants.remove(actorId);
      presenceByActor.remove(actorId);
      eventHub.actorLeft(sessionId, actor);
      confirmNonSemanticEvent(nextSequence, "actor leave");
      return snapshotLocked();
    } finally {
      lock.unlock();
    }
  }

  Workflow apply(
      CollaborationMode requestedMode, OperationActor actor, WorkflowOperation operation) {
    lock.lock();
    try {
      return applyLocked(requestedMode, actor, revision, operation);
    } finally {
      lock.unlock();
    }
  }

  Workflow apply(
      CollaborationMode requestedMode,
      OperationActor actor,
      long expectedRevision,
      WorkflowOperation operation) {
    lock.lock();
    try {
      return applyLocked(requestedMode, actor, expectedRevision, operation);
    } finally {
      lock.unlock();
    }
  }

  PresenceState updatePresence(OperationActor actor, PresenceState presenceState) {
    lock.lock();
    try {
      requireOpen();
      assertJoinedActor(actor);
      long nextSequence = reserveNonSemanticEvent();
      presenceByActor.put(actor.actorId(), presenceState);
      eventHub.presenceUpdated(sessionId, actor, presenceState);
      confirmNonSemanticEvent(nextSequence, "presence update");
      return presenceState;
    } finally {
      lock.unlock();
    }
  }

  Workflow workflow() {
    lock.lock();
    try {
      requireOpen();
      return operationLog.currentWorkflow();
    } finally {
      lock.unlock();
    }
  }

  WorkflowSessionRegistry.SessionSnapshot snapshot() {
    lock.lock();
    try {
      requireOpen();
      return snapshotLocked();
    } finally {
      lock.unlock();
    }
  }

  long close(String actorId) {
    lock.lock();
    try {
      requireOpen();
      if (!owner.actorId().equals(actorId)) {
        throw error(
            Code.SESSION_CLOSE_FORBIDDEN,
            "Only the session owner may close it: " + owner.actorId());
      }
      LifecycleState closedState = persistence.close(sessionId, revision, sequence);
      revision = closedState.revision();
      sequence = closedState.sequence();
      closed = true;
      return sequence;
    } finally {
      lock.unlock();
    }
  }

  void verifyClosedEvent(long finalSequence) {
    lock.lock();
    try {
      verifyEventHubState(finalSequence, revision, "session close");
    } finally {
      lock.unlock();
    }
  }

  OperationActor owner() {
    return owner;
  }

  private Workflow applyLocked(
      CollaborationMode requestedMode,
      OperationActor actor,
      long expectedRevision,
      WorkflowOperation operation) {
    requireOpen();
    assertModeAndActor(requestedMode, actor);
    OperationIdentity candidate = persistence.identity(operation);
    OperationIdentity previous = operationsById.get(operation.operationId());
    if (previous != null) {
      if (previous.equals(candidate)) {
        return operationLog.currentWorkflow();
      }
      throw duplicateOperation(operation.operationId());
    }
    if (expectedRevision != revision) {
      throw new WorkflowSessionRevisionConflictException(
          sessionId, expectedRevision, revision);
    }

    Workflow updatedWorkflow = operation.apply(operationLog.currentWorkflow());
    AppendOutcome outcome =
        persistence.append(sessionId, revision, sequence, operation, updatedWorkflow);
    revision = outcome.revision();
    sequence = outcome.sequence();
    if (outcome.duplicate()) {
      operationLog.reset(outcome.workflow());
      operationsById.put(operation.operationId(), outcome.identity());
      operationCount = Math.toIntExact(revision);
      return outcome.workflow();
    }

    Workflow appliedWorkflow = operationLog.apply(operation);
    if (!appliedWorkflow.equals(outcome.workflow())) {
      throw new IllegalStateException(
          "Persisted workflow differs from applied workflow for operation "
              + operation.operationId());
    }
    operationsById.put(operation.operationId(), outcome.identity());
    operationCount++;
    eventHub.operationAccepted(sessionId, actor, operation, appliedWorkflow);
    verifyEventHubState(sequence, revision, "accepted operation");
    return appliedWorkflow;
  }

  private long reserveNonSemanticEvent() {
    LifecycleState advanced = persistence.advanceEventSequence(sessionId, revision, sequence);
    if (advanced.closed() || advanced.revision() != revision) {
      throw new IllegalStateException(
          "Durable event sequence result is inconsistent for session " + sessionId);
    }
    return advanced.sequence();
  }

  private void confirmNonSemanticEvent(long expectedSequence, String eventDescription) {
    verifyEventHubState(expectedSequence, revision, eventDescription);
    sequence = expectedSequence;
  }

  private void verifyEventHubState(
      long expectedSequence, long expectedRevision, String eventDescription) {
    long actualSequence = eventHub.currentSequence(sessionId);
    long actualRevision = eventHub.currentRevision(sessionId);
    if (actualSequence != expectedSequence || actualRevision != expectedRevision) {
      throw new IllegalStateException(
          "Event hub state differs from session state after "
              + eventDescription
              + " for session "
              + sessionId
              + ": expected sequence/revision "
              + expectedSequence
              + "/"
              + expectedRevision
              + " but found "
              + actualSequence
              + "/"
              + actualRevision);
    }
  }

  private void assertModeAndActor(CollaborationMode requestedMode, OperationActor actor) {
    if (mode != requestedMode) {
      throw error(
          Code.SESSION_MODE_MISMATCH,
          "Requested mode '" + requestedMode + "' does not match session mode '" + mode + "'");
    }
    assertJoinedActor(actor);
  }

  private void assertJoinedActor(OperationActor actor) {
    OperationActor joinedActor = participants.get(actor.actorId());
    if (joinedActor == null) {
      throw error(Code.ACTOR_NOT_JOINED, "Actor is not joined: " + actor.actorId());
    }
    if (!joinedActor.equals(actor)) {
      throw error(Code.ACTOR_METADATA_MISMATCH, "Actor metadata mismatch: " + actor.actorId());
    }
  }

  private WorkflowSessionException duplicateOperation(String operationId) {
    return error(
        Code.DUPLICATE_OPERATION_ID,
        "Operation id is already associated with different content: " + operationId);
  }

  private WorkflowSessionException error(Code code, String message) {
    return new WorkflowSessionException(code, sessionId, message);
  }

  private void requireOpen() {
    if (closed) {
      throw error(Code.SESSION_NOT_FOUND, "Unknown session: " + sessionId);
    }
  }

  private WorkflowSessionRegistry.SessionSnapshot snapshotLocked() {
    requireOpen();
    List<OperationActor> actors = new ArrayList<>(participants.values());
    actors.sort(Comparator.comparing(OperationActor::actorId));
    return new WorkflowSessionRegistry.SessionSnapshot(
        sessionId,
        mode,
        owner,
        createdAt,
        actors,
        operationCount,
        operationLog.currentWorkflow().id(),
        revision,
        sequence);
  }

  private record SessionDefinition(
      String sessionId,
      CollaborationMode mode,
      OperationActor owner,
      Instant createdAt,
      Workflow workflow) {

    SessionDefinition {
      Objects.requireNonNull(sessionId, "sessionId");
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(createdAt, "createdAt");
      Objects.requireNonNull(workflow, "workflow");
    }
  }

  private record RecoveryState(
      List<OperationIdentity> operations,
      boolean ownerConnected,
      long revision,
      long sequence) {

    RecoveryState {
      operations = List.copyOf(Objects.requireNonNull(operations, "operations"));
      if (revision < 0 || sequence < revision) {
        throw new IllegalArgumentException("Invalid recovery revision/event sequence");
      }
    }
  }

  private record Services(
      WorkflowSessionEventHub eventHub, WorkflowSessionPersistenceCoordinator persistence) {

    Services {
      Objects.requireNonNull(eventHub, "eventHub");
      Objects.requireNonNull(persistence, "persistence");
    }
  }
}
