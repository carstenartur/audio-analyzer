package org.hammer.audio.workflow.collaboration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.collaboration.WorkflowSessionException.Code;
import org.hammer.audio.workflow.collaboration.WorkflowSessionPersistenceCoordinator.AppendOutcome;
import org.hammer.audio.workflow.collaboration.WorkflowSessionPersistenceCoordinator.OperationIdentity;

/** Owns the synchronized runtime state for one collaboration session. */
final class WorkflowSessionEntry {

  private final String sessionId;
  private final CollaborationMode mode;
  private final OperationActor sessionOwner;
  private final Instant createdAt;
  private final WorkflowSessionEventHub eventHub;
  private final WorkflowSessionPersistenceCoordinator persistence;
  private final WorkflowSessionIndex<String, OperationActor> participants =
      new WorkflowSessionIndex<>();
  private final WorkflowSessionIndex<String, OperationIdentity> operationsById =
      new WorkflowSessionIndex<>();
  private final ReentrantLock lock = new ReentrantLock();
  private Workflow currentWorkflow;
  private int operationCount;
  private long revision;
  private long sequence;
  private volatile boolean closed;

  private WorkflowSessionEntry(
      SessionDefinition definition, RecoveryState recovery, Services services) {
    this.sessionId = definition.sessionId();
    this.mode = definition.mode();
    this.sessionOwner = definition.owner();
    this.createdAt = definition.createdAt();
    this.currentWorkflow = definition.workflow();
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
      participants.put(sessionOwner.actorId(), sessionOwner);
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
      SessionDefinition definition,
      RecoveryState recovery,
      WorkflowSessionEventHub eventHub,
      WorkflowSessionPersistenceCoordinator persistence) {
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
          && !sessionOwner.actorId().equals(actor.actorId())) {
        throw error(
            Code.PRIVATE_WORKSPACE_ACCESS_DENIED,
            "Private workspace can only be joined by its owner: " + sessionOwner.actorId());
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
      return currentWorkflow;
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
      if (!sessionOwner.actorId().equals(actorId)) {
        throw error(
            Code.SESSION_CLOSE_FORBIDDEN,
            "Only the session owner may close it: " + sessionOwner.actorId());
      }
      sequence = persistence.close(sessionId, revision, sequence);
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
    return sessionOwner;
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
        return currentWorkflow;
      }
      throw duplicateOperation(operation.operationId());
    }
    persistence.requireExpectedRevision(sessionId, expectedRevision, revision);

    Workflow updatedWorkflow = operation.apply(currentWorkflow);
    AppendOutcome outcome =
        persistence.append(sessionId, revision, sequence, operation, updatedWorkflow);
    revision = outcome.revision();
    sequence = outcome.sequence();
    currentWorkflow = outcome.workflow();
    if (outcome.duplicate()) {
      operationsById.put(operation.operationId(), outcome.identity());
      operationCount = Math.toIntExact(revision);
      return currentWorkflow;
    }

    if (!updatedWorkflow.equals(currentWorkflow)) {
      throw new IllegalStateException(
          "Persisted workflow differs from applied workflow for operation "
              + operation.operationId());
    }
    operationsById.put(operation.operationId(), outcome.identity());
    operationCount++;
    eventHub.operationAccepted(sessionId, actor, operation, currentWorkflow);
    verifyEventHubState(sequence, revision, "accepted operation");
    return currentWorkflow;
  }

  private long reserveNonSemanticEvent() {
    return persistence.advanceEventSequence(sessionId, revision, sequence);
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
        sessionOwner,
        createdAt,
        actors,
        operationCount,
        currentWorkflow.id(),
        revision,
        sequence);
  }

  record SessionDefinition(
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

  record RecoveryState(
      List<OperationIdentity> operations, boolean ownerConnected, long revision, long sequence) {

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
