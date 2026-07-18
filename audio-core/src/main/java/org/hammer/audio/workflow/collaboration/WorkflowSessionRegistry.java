package org.hammer.audio.workflow.collaboration;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.collaboration.WorkflowSessionException.Code;
import org.hammer.audio.workflow.collaboration.WorkflowSessionPersistenceCoordinator.RecoveredSession;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionStateStore;

/**
 * Thread-safe application service for collaboration-session lifecycle and actor membership.
 *
 * <p>Sessions retain their canonical workflow when the last participant leaves, so actors can
 * reconnect. A session is removed only by an explicit owner close operation. Session mode is
 * immutable for the complete lifetime of a session.
 *
 * <p>When a durable store is supplied, open sessions are recovered without restoring transport
 * connections or presence state and without replaying historical events as new events.
 */
public final class WorkflowSessionRegistry {

  private static final String SESSION_ID_FIELD = "sessionId";
  private static final long INITIAL_SESSION_EVENT_SEQUENCE = 2;

  private final Map<String, WorkflowSessionEntry> sessionEntries = new ConcurrentHashMap<>();
  private final WorkflowSessionEventHub sessionEventHub;
  private final WorkflowSessionPersistenceCoordinator persistence;

  /** Creates a registry with an in-memory bounded session-event hub. */
  public WorkflowSessionRegistry() {
    this(new WorkflowSessionEventHub());
  }

  /** Creates a registry publishing lifecycle and accepted-operation events to the supplied hub. */
  public WorkflowSessionRegistry(WorkflowSessionEventHub eventHub) {
    this.sessionEventHub = Objects.requireNonNull(eventHub, "eventHub");
    this.persistence = WorkflowSessionPersistenceCoordinator.inMemory();
  }

  /**
   * Creates a registry backed by durable collaboration state and recovers all open sessions.
   *
   * @param eventHub transport-neutral event hub
   * @param stateStore durable collaboration state store, or {@code null} for in-memory operation
   */
  public WorkflowSessionRegistry(
      WorkflowSessionEventHub eventHub, WorkflowSessionStateStore stateStore) {
    this.sessionEventHub = Objects.requireNonNull(eventHub, "eventHub");
    this.persistence =
        stateStore == null
            ? WorkflowSessionPersistenceCoordinator.inMemory()
            : WorkflowSessionPersistenceCoordinator.durable(stateStore);
    recoverDurableSessions();
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
    WorkflowSessionEntry created =
        WorkflowSessionEntry.created(
            requiredSessionId,
            mode,
            owner,
            createdAt,
            initialWorkflow,
            sessionEventHub,
            persistence);
    WorkflowSessionEntry previous = sessionEntries.putIfAbsent(requiredSessionId, created);
    if (previous != null) {
      throw error(
          Code.SESSION_ALREADY_EXISTS,
          requiredSessionId,
          "Session already exists: " + requiredSessionId);
    }

    boolean persisted = false;
    try {
      persistence.create(
          requiredSessionId,
          mode,
          owner,
          createdAt,
          initialWorkflow,
          INITIAL_SESSION_EVENT_SEQUENCE);
      persisted = persistence.durable();
      sessionEventHub.openSession(requiredSessionId, owner, initialWorkflow);
      created.verifyInitialEventStream(INITIAL_SESSION_EVENT_SEQUENCE);
      return created.snapshot();
    } catch (RuntimeException failure) {
      sessionEntries.remove(requiredSessionId, created);
      if (persisted) {
        persistence.compensateFailedCreate(
            requiredSessionId, 0, INITIAL_SESSION_EVENT_SEQUENCE, failure);
      }
      throw new IllegalStateException(
          "Failed to create collaboration session " + requiredSessionId, failure);
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

  /** Applies an actor-authored semantic operation at the current server revision. */
  public Workflow applyOperation(
      String sessionId, CollaborationMode mode, OperationActor actor, WorkflowOperation operation) {
    assertOperationAuthor(sessionId, actor, operation);
    return requireSession(sessionId).apply(mode, actor, operation);
  }

  /**
   * Applies an actor-authored semantic operation against an explicit client-observed revision.
   *
   * <p>An identical command retry remains idempotent even when its expected revision precedes the
   * current revision.
   */
  public Workflow applyOperation(
      String sessionId,
      CollaborationMode mode,
      OperationActor actor,
      long expectedRevision,
      WorkflowOperation operation) {
    if (expectedRevision < 0) {
      throw new IllegalArgumentException("expectedRevision must be >= 0");
    }
    assertOperationAuthor(sessionId, actor, operation);
    return requireSession(sessionId).apply(mode, actor, expectedRevision, operation);
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
    WorkflowSessionEntry entry = requireSession(requiredSessionId);
    long finalSequence = entry.close(actorId);
    if (!sessionEntries.remove(requiredSessionId, entry)) {
      throw error(
          Code.SESSION_NOT_FOUND,
          requiredSessionId,
          "Session changed while closing: " + requiredSessionId);
    }
    sessionEventHub.closeSession(requiredSessionId, entry.owner());
    entry.verifyClosedEvent(finalSequence);
  }

  /** Returns all current sessions in stable identifier order. */
  public List<SessionSnapshot> sessions() {
    return sessionEntries.values().stream()
        .map(WorkflowSessionEntry::snapshot)
        .sorted(Comparator.comparing(SessionSnapshot::sessionId))
        .toList();
  }

  private void recoverDurableSessions() {
    for (RecoveredSession recovered : persistence.recoverOpenSessions()) {
      WorkflowSessionEntry.SessionDefinition definition =
          new WorkflowSessionEntry.SessionDefinition(
              recovered.sessionId(),
              recovered.mode(),
              recovered.owner(),
              recovered.createdAt(),
              recovered.workflow());
      WorkflowSessionEntry.RecoveryState recoveryState =
          new WorkflowSessionEntry.RecoveryState(
              recovered.operations(), false, recovered.revision(), recovered.sequence());
      WorkflowSessionEntry entry =
          WorkflowSessionEntry.recovered(definition, recoveryState, sessionEventHub, persistence);
      if (sessionEntries.putIfAbsent(recovered.sessionId(), entry) != null) {
        throw new WorkflowSessionRecoveryException(
            recovered.sessionId(),
            "Duplicate durable collaboration session: " + recovered.sessionId());
      }
      try {
        sessionEventHub.restoreSession(
            recovered.sessionId(),
            recovered.workflow(),
            recovered.sequence(),
            recovered.revision());
      } catch (RuntimeException failure) {
        sessionEntries.remove(recovered.sessionId(), entry);
        throw new WorkflowSessionRecoveryException(
            recovered.sessionId(),
            "Failed to restore collaboration event stream for session " + recovered.sessionId(),
            failure);
      }
    }
  }

  private WorkflowSessionEntry requireSession(String sessionId) {
    String requiredSessionId = requireNotBlank(sessionId, SESSION_ID_FIELD);
    WorkflowSessionEntry entry = sessionEntries.get(requiredSessionId);
    if (entry == null) {
      throw error(
          Code.SESSION_NOT_FOUND, requiredSessionId, "Unknown session: " + requiredSessionId);
    }
    return entry;
  }

  private static void assertOperationAuthor(
      String sessionId, OperationActor actor, WorkflowOperation operation) {
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
   * @param revision current semantic workflow revision
   * @param sequence latest collaboration-event sequence
   */
  public record SessionSnapshot(
      String sessionId,
      CollaborationMode mode,
      OperationActor owner,
      Instant createdAt,
      List<OperationActor> participants,
      int operationCount,
      String workflowId,
      long revision,
      long sequence) {

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
      if (revision < 0 || sequence < revision) {
        throw new IllegalArgumentException("Invalid session revision/event sequence");
      }
    }
  }
}
