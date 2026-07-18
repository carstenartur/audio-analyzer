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
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowOperation;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowSession;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationPersistenceCodec;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationPersistenceData;
import org.hammer.audio.workflow.collaboration.store.WorkflowOutboxEventData;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionAppendCommand;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionAppendResult;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionStateStore;
import org.hammer.audio.workflow.dsl.WorkflowDslParser;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;

/**
 * Thread-safe application service for collaboration-session lifecycle and actor membership.
 *
 * <p>Sessions retain their canonical {@link WorkflowOperationLog} when the last participant leaves,
 * so actors can reconnect. A session is removed only by an explicit owner close operation. Session
 * mode is immutable for the complete lifetime of a session.
 *
 * <p>When a durable store is supplied, the registry recovers open canonical snapshots and accepted
 * operation identities. Connection membership and presence intentionally start empty after restart.
 */
public final class WorkflowSessionRegistry {

  private static final String SESSION_ID_FIELD = "sessionId";
  private static final String OPERATION_EVENT_TYPE = "WORKFLOW_OPERATION_ACCEPTED";

  private final Map<String, SessionEntry> sessionEntries = new ConcurrentHashMap<>();
  private final WorkflowSessionEventHub sessionEventHub;
  private final WorkflowSessionStateStore stateStore;
  private final WorkflowDslParser dslParser = new WorkflowDslParser();
  private final WorkflowDslSerializer dslSerializer = new WorkflowDslSerializer();

  /** Creates a registry with an in-memory bounded session-event hub. */
  public WorkflowSessionRegistry() {
    this(new WorkflowSessionEventHub());
  }

  /** Creates a registry publishing lifecycle and accepted-operation events to the supplied hub. */
  public WorkflowSessionRegistry(WorkflowSessionEventHub eventHub) {
    this(eventHub, null);
  }

  /**
   * Creates a registry backed by durable collaboration state and recovers all open sessions.
   *
   * @param eventHub transport-neutral event hub
   * @param stateStore durable collaboration state store
   */
  public WorkflowSessionRegistry(
      WorkflowSessionEventHub eventHub, WorkflowSessionStateStore stateStore) {
    this.sessionEventHub = Objects.requireNonNull(eventHub, "eventHub");
    this.stateStore = stateStore;
    if (stateStore != null) {
      recoverDurableSessions();
    }
  }

  /** Returns the transport-neutral event hub used by this registry. */
  public WorkflowSessionEventHub eventHub() {
    return sessionEventHub;
  }

  /** Creates a new session and joins its owner. */
  public SessionSnapshot create(
      String sessionId, CollaborationMode mode, OperationActor owner, Workflow initialWorkflow) {
    String requiredSessionId = requireNotBlank(sessionId, SESSION_ID_FIELD);
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(initialWorkflow, "initialWorkflow");
    Instant createdAt = Instant.now();
    SessionEntry created =
        SessionEntry.created(
            requiredSessionId,
            mode,
            owner,
            createdAt,
            initialWorkflow,
            sessionEventHub,
            stateStore,
            dslParser,
            dslSerializer);
    SessionEntry previous = sessionEntries.putIfAbsent(requiredSessionId, created);
    if (previous != null) {
      throw error(
          Code.SESSION_ALREADY_EXISTS,
          requiredSessionId,
          "Session already exists: " + requiredSessionId);
    }

    boolean persisted = false;
    try {
      if (stateStore != null) {
        stateStore.create(
            new StoredWorkflowSession(
                requiredSessionId,
                mode,
                owner,
                createdAt,
                initialWorkflow.id(),
                dslSerializer.serialize(initialWorkflow),
                0,
                0,
                false));
        persisted = true;
      }
      sessionEventHub.openSession(requiredSessionId, owner, initialWorkflow);
      return created.snapshot();
    } catch (RuntimeException failure) {
      sessionEntries.remove(requiredSessionId, created);
      if (persisted) {
        stateStore.close(requiredSessionId, 0);
      }
      throw failure;
    }
  }

  /** Joins an existing session. Duplicate joins with identical actor metadata are idempotent. */
  public SessionSnapshot join(String sessionId, OperationActor actor) {
    return requireSession(sessionId).join(Objects.requireNonNull(actor, "actor"));
  }

  /** Leaves a session while retaining it for reconnect until explicitly closed. */
  public SessionSnapshot leave(String sessionId, String actorId) {
    return requireSession(sessionId).leave(requireNotBlank(actorId, "actorId"));
  }

  /** Returns immutable session metadata. */
  public SessionSnapshot inspect(String sessionId) {
    return requireSession(sessionId).snapshot();
  }

  /** Returns the current server-authoritative workflow. */
  public Workflow workflow(String sessionId) {
    return requireSession(sessionId).workflow();
  }

  /** Applies an actor-authored semantic operation to an existing joined session. */
  public Workflow applyOperation(
      String sessionId, CollaborationMode mode, OperationActor actor, WorkflowOperation operation) {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(operation, "operation");
    if (!operation.author().equals(actor.actorId())) {
      throw error(
          Code.INVALID_OPERATION_AUTHOR,
          sessionId,
          "Operation author '"
              + operation.author()
              + "' does not match actor '"
              + actor.actorId()
              + "'");
    }
    return requireSession(sessionId).apply(mode, actor, operation);
  }

  /** Updates non-semantic presence for a joined actor. */
  public PresenceState updatePresence(
      String sessionId, OperationActor actor, PresenceState presenceState) {
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(presenceState, "presenceState");
    if (!presenceState.actorId().equals(actor.actorId())) {
      throw error(
          Code.ACTOR_METADATA_MISMATCH,
          sessionId,
          "Presence actor '"
              + presenceState.actorId()
              + "' does not match actor '"
              + actor.actorId()
              + "'");
    }
    return requireSession(sessionId).updatePresence(actor, presenceState);
  }

  /** Explicitly closes a session. Only its owner may close it. */
  public void close(String sessionId, String requestedByActorId) {
    String requiredSessionId = requireNotBlank(sessionId, SESSION_ID_FIELD);
    String actorId = requireNotBlank(requestedByActorId, "requestedByActorId");
    SessionEntry entry = requireSession(requiredSessionId);
    entry.close(actorId);
    if (!sessionEntries.remove(requiredSessionId, entry)) {
      throw error(
          Code.SESSION_NOT_FOUND,
          requiredSessionId,
          "Session changed while closing: " + requiredSessionId);
    }
    sessionEventHub.closeSession(requiredSessionId, entry.sessionOwner());
  }

  /** Returns all current sessions in stable identifier order. */
  public List<SessionSnapshot> sessions() {
    return sessionEntries.values().stream()
        .map(SessionEntry::snapshot)
        .sorted(Comparator.comparing(SessionSnapshot::sessionId))
        .toList();
  }

  private void recoverDurableSessions() {
    for (StoredWorkflowSession storedSession : stateStore.openSessions()) {
      recoverDurableSession(storedSession);
    }
  }

  private void recoverDurableSession(StoredWorkflowSession storedSession) {
    String sessionId = storedSession.sessionId();
    try {
      Workflow workflow = dslParser.parse(storedSession.workflowDsl());
      if (!workflow.id().equals(storedSession.workflowId())) {
        throw new WorkflowSessionRecoveryException(
            sessionId,
            "Recovered workflow id '"
                + workflow.id()
                + "' does not match durable id '"
                + storedSession.workflowId()
                + "'");
      }
      List<StoredWorkflowOperation> operations = stateStore.operations(sessionId);
      validateRecoveredHistory(storedSession, operations);
      SessionEntry recovered =
          SessionEntry.recovered(
              storedSession,
              workflow,
              operations,
              sessionEventHub,
              stateStore,
              dslParser,
              dslSerializer);
      if (sessionEntries.putIfAbsent(sessionId, recovered) != null) {
        throw new WorkflowSessionRecoveryException(
            sessionId, "Duplicate durable collaboration session: " + sessionId);
      }
      try {
        sessionEventHub.restoreSession(
            sessionId, workflow, storedSession.sequence(), storedSession.revision());
      } catch (RuntimeException failure) {
        sessionEntries.remove(sessionId, recovered);
        throw failure;
      }
    } catch (WorkflowSessionRecoveryException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new WorkflowSessionRecoveryException(
          sessionId, "Failed to recover durable collaboration session " + sessionId, failure);
    }
  }

  private static void validateRecoveredHistory(
      StoredWorkflowSession session, List<StoredWorkflowOperation> operations) {
    long expectedPosition = 1;
    for (StoredWorkflowOperation operation : operations) {
      if (!operation.sessionId().equals(session.sessionId())) {
        throw new WorkflowSessionRecoveryException(
            session.sessionId(),
            "Durable operation belongs to a different session: " + operation.operationId());
      }
      if (operation.sequence() != expectedPosition || operation.revision() != expectedPosition) {
        throw new WorkflowSessionRecoveryException(
            session.sessionId(),
            "Durable operation history is not contiguous at operation " + operation.operationId());
      }
      expectedPosition++;
    }
    long historySize = operations.size();
    if (session.revision() != historySize || session.sequence() != historySize) {
      throw new WorkflowSessionRecoveryException(
          session.sessionId(),
          "Durable aggregate revision/sequence does not match operation history for session "
              + session.sessionId());
    }
  }

  private SessionEntry requireSession(String sessionId) {
    String requiredSessionId = requireNotBlank(sessionId, SESSION_ID_FIELD);
    SessionEntry entry = sessionEntries.get(requiredSessionId);
    if (entry == null) {
      throw error(
          Code.SESSION_NOT_FOUND, requiredSessionId, "Unknown session: " + requiredSessionId);
    }
    return entry;
  }

  private static WorkflowSessionException error(Code code, String sessionId, String message) {
    return new WorkflowSessionException(code, sessionId, message);
  }

  private static String requireNotBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  private static final class SessionEntry {
    private final String sessionId;
    private final CollaborationMode mode;
    private final OperationActor owner;
    private final Instant createdAt;
    private final WorkflowOperationLog operationLog;
    private final CollaborativeWorkflowSessionService sessionService;
    private final WorkflowSessionEventHub eventHub;
    private final WorkflowSessionStateStore stateStore;
    private final WorkflowDslParser dslParser;
    private final WorkflowDslSerializer dslSerializer;
    private final Map<String, OperationActor> participants = new ConcurrentHashMap<>();
    private final Map<String, AcceptedOperationIdentity> operationsById = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private int operationCount;
    private long durableRevision;
    private long durableSequence;
    private volatile boolean closed;

    private SessionEntry(
        String sessionId,
        CollaborationMode mode,
        OperationActor owner,
        Instant createdAt,
        Workflow workflow,
        List<StoredWorkflowOperation> recoveredOperations,
        boolean ownerConnected,
        long durableRevision,
        long durableSequence,
        WorkflowSessionEventHub eventHub,
        WorkflowSessionStateStore stateStore,
        WorkflowDslParser dslParser,
        WorkflowDslSerializer dslSerializer) {
      this.sessionId = sessionId;
      this.mode = mode;
      this.owner = owner;
      this.createdAt = createdAt;
      this.eventHub = Objects.requireNonNull(eventHub, "eventHub");
      this.stateStore = stateStore;
      this.dslParser = Objects.requireNonNull(dslParser, "dslParser");
      this.dslSerializer = Objects.requireNonNull(dslSerializer, "dslSerializer");
      this.operationLog = new WorkflowOperationLog(workflow);
      this.sessionService =
          new CollaborativeWorkflowSessionService(
              sessionId,
              mode,
              operationLog,
              new InMemoryWorkflowEventOutbox(),
              ignored -> ignoreEvent());
      this.durableRevision = durableRevision;
      this.durableSequence = durableSequence;
      for (StoredWorkflowOperation operation : recoveredOperations) {
        AcceptedOperationIdentity previous =
            operationsById.putIfAbsent(
                operation.operationId(), AcceptedOperationIdentity.from(operation));
        if (previous != null) {
          throw new WorkflowSessionRecoveryException(
              sessionId, "Duplicate durable operation id: " + operation.operationId());
        }
      }
      this.operationCount = recoveredOperations.size();
      if (ownerConnected) {
        participants.put(owner.actorId(), owner);
      }
    }

    static SessionEntry created(
        String sessionId,
        CollaborationMode mode,
        OperationActor owner,
        Instant createdAt,
        Workflow workflow,
        WorkflowSessionEventHub eventHub,
        WorkflowSessionStateStore stateStore,
        WorkflowDslParser dslParser,
        WorkflowDslSerializer dslSerializer) {
      return new SessionEntry(
          sessionId,
          mode,
          owner,
          createdAt,
          workflow,
          List.of(),
          true,
          0,
          0,
          eventHub,
          stateStore,
          dslParser,
          dslSerializer);
    }

    static SessionEntry recovered(
        StoredWorkflowSession storedSession,
        Workflow workflow,
        List<StoredWorkflowOperation> recoveredOperations,
        WorkflowSessionEventHub eventHub,
        WorkflowSessionStateStore stateStore,
        WorkflowDslParser dslParser,
        WorkflowDslSerializer dslSerializer) {
      return new SessionEntry(
          storedSession.sessionId(),
          storedSession.mode(),
          storedSession.owner(),
          storedSession.createdAt(),
          workflow,
          recoveredOperations,
          false,
          storedSession.revision(),
          storedSession.sequence(),
          eventHub,
          stateStore,
          dslParser,
          dslSerializer);
    }

    private static void ignoreEvent() {
      // no-op
    }

    SessionSnapshot join(OperationActor actor) {
      lock.lock();
      try {
        requireOpen();
        if (mode == CollaborationMode.PRIVATE_WORKSPACE
            && !owner.actorId().equals(actor.actorId())) {
          throw error(
              Code.PRIVATE_WORKSPACE_ACCESS_DENIED,
              sessionId,
              "Private workspace can only be joined by its owner: " + owner.actorId());
        }
        OperationActor existing = participants.get(actor.actorId());
        if (existing != null && !existing.equals(actor)) {
          throw error(
              Code.ACTOR_METADATA_MISMATCH,
              sessionId,
              "Actor metadata mismatch for already joined actor: " + actor.actorId());
        }
        if (participants.putIfAbsent(actor.actorId(), actor) == null) {
          eventHub.actorJoined(sessionId, actor);
        }
        return snapshotLocked();
      } finally {
        lock.unlock();
      }
    }

    SessionSnapshot leave(String actorId) {
      lock.lock();
      try {
        requireOpen();
        if (!participants.containsKey(actorId)) {
          throw error(Code.ACTOR_NOT_JOINED, sessionId, "Actor is not joined: " + actorId);
        }
        OperationActor actor = participants.remove(actorId);
        sessionService.clearPresence(actorId);
        eventHub.actorLeft(sessionId, actor);
        return snapshotLocked();
      } finally {
        lock.unlock();
      }
    }

    Workflow apply(
        CollaborationMode requestedMode, OperationActor actor, WorkflowOperation operation) {
      lock.lock();
      try {
        requireOpen();
        assertModeAndActor(requestedMode, actor);
        AcceptedOperationIdentity candidate = AcceptedOperationIdentity.from(operation);
        AcceptedOperationIdentity previous = operationsById.get(operation.operationId());
        if (previous != null) {
          if (previous.equals(candidate)) {
            return operationLog.currentWorkflow();
          }
          throw duplicateOperation(operation.operationId());
        }

        Workflow updatedWorkflow = operation.apply(operationLog.currentWorkflow());
        if (stateStore != null) {
          WorkflowSessionAppendResult durableResult = persist(operation, updatedWorkflow);
          if (durableResult.duplicate()) {
            Workflow durableWorkflow = dslParser.parse(durableResult.session().workflowDsl());
            operationLog.reset(durableWorkflow);
            operationsById.put(operation.operationId(), candidate);
            operationCount++;
            durableRevision = durableResult.session().revision();
            durableSequence = durableResult.session().sequence();
            return durableWorkflow;
          }
          durableRevision = durableResult.session().revision();
          durableSequence = durableResult.session().sequence();
        }

        Workflow appliedWorkflow = operationLog.apply(operation);
        if (!appliedWorkflow.equals(updatedWorkflow)) {
          throw new IllegalStateException(
              "Persisted workflow differs from applied workflow for operation "
                  + operation.operationId());
        }
        operationsById.put(operation.operationId(), candidate);
        operationCount++;
        WorkflowSessionEvent accepted =
            eventHub.operationAccepted(sessionId, actor, operation, appliedWorkflow);
        if (stateStore == null) {
          durableRevision = accepted.revision();
          durableSequence = accepted.sequence();
        } else if (accepted.revision() != durableRevision) {
          throw new IllegalStateException(
              "Event revision differs from durable revision for session " + sessionId);
        }
        return appliedWorkflow;
      } finally {
        lock.unlock();
      }
    }

    PresenceState updatePresence(OperationActor actor, PresenceState presenceState) {
      lock.lock();
      try {
        requireOpen();
        OperationActor joinedActor = participants.get(actor.actorId());
        if (joinedActor == null) {
          throw error(Code.ACTOR_NOT_JOINED, sessionId, "Actor is not joined: " + actor.actorId());
        }
        if (!joinedActor.equals(actor)) {
          throw error(
              Code.ACTOR_METADATA_MISMATCH,
              sessionId,
              "Actor metadata mismatch: " + actor.actorId());
        }
        sessionService.updatePresence(presenceState);
        eventHub.presenceUpdated(sessionId, actor, presenceState);
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

    SessionSnapshot snapshot() {
      lock.lock();
      try {
        requireOpen();
        return snapshotLocked();
      } finally {
        lock.unlock();
      }
    }

    void close(String actorId) {
      lock.lock();
      try {
        requireOpen();
        if (!owner.actorId().equals(actorId)) {
          throw error(
              Code.SESSION_CLOSE_FORBIDDEN,
              sessionId,
              "Only the session owner may close it: " + owner.actorId());
        }
        if (stateStore != null) {
          stateStore.close(sessionId, durableRevision);
        }
        closed = true;
      } finally {
        lock.unlock();
      }
    }

    OperationActor sessionOwner() {
      return owner;
    }

    private WorkflowSessionAppendResult persist(
        WorkflowOperation operation, Workflow updatedWorkflow) {
      WorkflowOperationPersistenceData persistenceData =
          WorkflowOperationPersistenceCodec.encode(operation);
      long nextSequence = Math.addExact(durableSequence, 1);
      WorkflowOutboxEventData outboxEvent =
          new WorkflowOutboxEventData(
              sessionId + ":" + nextSequence,
              OPERATION_EVENT_TYPE,
              operation.timestamp(),
              persistenceData.payload());
      return stateStore.append(
          new WorkflowSessionAppendCommand(
              sessionId,
              durableRevision,
              persistenceData,
              updatedWorkflow.id(),
              dslSerializer.serialize(updatedWorkflow),
              outboxEvent));
    }

    private void assertModeAndActor(CollaborationMode requestedMode, OperationActor actor) {
      if (mode != requestedMode) {
        throw error(
            Code.SESSION_MODE_MISMATCH,
            sessionId,
            "Requested mode '" + requestedMode + "' does not match session mode '" + mode + "'");
      }
      OperationActor joinedActor = participants.get(actor.actorId());
      if (joinedActor == null) {
        throw error(Code.ACTOR_NOT_JOINED, sessionId, "Actor is not joined: " + actor.actorId());
      }
      if (!joinedActor.equals(actor)) {
        throw error(
            Code.ACTOR_METADATA_MISMATCH,
            sessionId,
            "Actor metadata mismatch: " + actor.actorId());
      }
    }

    private WorkflowSessionException duplicateOperation(String operationId) {
      return error(
          Code.DUPLICATE_OPERATION_ID,
          sessionId,
          "Operation id is already associated with different content: " + operationId);
    }

    private void requireOpen() {
      if (closed) {
        throw error(Code.SESSION_NOT_FOUND, sessionId, "Unknown session: " + sessionId);
      }
    }

    private SessionSnapshot snapshotLocked() {
      requireOpen();
      List<OperationActor> actors = new ArrayList<>(participants.values());
      actors.sort(Comparator.comparing(OperationActor::actorId));
      return new SessionSnapshot(
          sessionId,
          mode,
          owner,
          createdAt,
          actors,
          operationCount,
          operationLog.currentWorkflow().id());
    }
  }

  private record AcceptedOperationIdentity(String operationType, String actorId, String payload) {

    static AcceptedOperationIdentity from(WorkflowOperation operation) {
      WorkflowOperationPersistenceData encoded = WorkflowOperationPersistenceCodec.encode(operation);
      return new AcceptedOperationIdentity(
          encoded.operationType(), encoded.actorId(), encoded.payload());
    }

    static AcceptedOperationIdentity from(StoredWorkflowOperation operation) {
      return new AcceptedOperationIdentity(
          operation.operationType(), operation.actorId(), operation.payload());
    }
  }

  /**
   * Immutable transport-neutral session metadata.
   *
   * @param sessionId stable unique session identifier
   * @param mode immutable collaboration mode
   * @param owner owner actor metadata
   * @param createdAt session creation timestamp
   * @param participants currently joined participants
   * @param operationCount number of applied operations
   * @param workflowId identifier of the canonical workflow
   */
  public record SessionSnapshot(
      String sessionId,
      CollaborationMode mode,
      OperationActor owner,
      Instant createdAt,
      List<OperationActor> participants,
      int operationCount,
      String workflowId) {

    public SessionSnapshot {
      requireNotBlank(sessionId, "sessionId");
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(createdAt, "createdAt");
      participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
      if (operationCount < 0) {
        throw new IllegalArgumentException("operationCount must be >= 0");
      }
      requireNotBlank(workflowId, "workflowId");
    }
  }
}
