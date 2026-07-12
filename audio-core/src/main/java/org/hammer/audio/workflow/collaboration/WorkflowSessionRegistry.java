package org.hammer.audio.workflow.collaboration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.WorkflowOperationLog;

/**
 * Thread-safe application service for collaboration-session lifecycle and actor membership.
 *
 * <p>Sessions retain their canonical {@link WorkflowOperationLog} when the last participant leaves,
 * so actors can reconnect. A session is removed only by an explicit owner close operation. Session
 * mode is immutable for the complete lifetime of a session.
 */
public final class WorkflowSessionRegistry {

  private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();

  /** Creates a new session and joins its owner. */
  public SessionSnapshot create(
      String sessionId,
      CollaborationMode mode,
      OperationActor owner,
      Workflow initialWorkflow) {
    String requiredSessionId = requireNotBlank(sessionId, "sessionId");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(initialWorkflow, "initialWorkflow");
    SessionEntry created = new SessionEntry(requiredSessionId, mode, owner, initialWorkflow);
    SessionEntry previous = sessions.putIfAbsent(requiredSessionId, created);
    if (previous != null) {
      throw new IllegalStateException("Session already exists: " + requiredSessionId);
    }
    return created.snapshot();
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
      String sessionId,
      CollaborationMode mode,
      OperationActor actor,
      WorkflowOperation operation) {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(operation, "operation");
    if (!operation.author().equals(actor.actorId())) {
      throw new IllegalArgumentException(
          "operation author '"
              + operation.author()
              + "' does not match actor '"
              + actor.actorId()
              + "'");
    }
    return requireSession(sessionId).apply(mode, actor, operation);
  }

  /** Explicitly closes a session. Only its owner may close it. */
  public void close(String sessionId, String requestedByActorId) {
    String requiredSessionId = requireNotBlank(sessionId, "sessionId");
    String actorId = requireNotBlank(requestedByActorId, "requestedByActorId");
    SessionEntry entry = requireSession(requiredSessionId);
    entry.assertOwner(actorId);
    if (!sessions.remove(requiredSessionId, entry)) {
      throw new IllegalStateException("Session changed while closing: " + requiredSessionId);
    }
  }

  /** Returns all current sessions in stable identifier order. */
  public List<SessionSnapshot> sessions() {
    return sessions.values().stream()
        .map(SessionEntry::snapshot)
        .sorted(Comparator.comparing(SessionSnapshot::sessionId))
        .toList();
  }

  private SessionEntry requireSession(String sessionId) {
    String requiredSessionId = requireNotBlank(sessionId, "sessionId");
    SessionEntry entry = sessions.get(requiredSessionId);
    if (entry == null) {
      throw new IllegalArgumentException("Unknown session: " + requiredSessionId);
    }
    return entry;
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
    private final Map<String, OperationActor> participants = new LinkedHashMap<>();

    SessionEntry(
        String sessionId,
        CollaborationMode mode,
        OperationActor owner,
        Workflow initialWorkflow) {
      this.sessionId = sessionId;
      this.mode = mode;
      this.owner = owner;
      this.createdAt = Instant.now();
      this.operationLog = new WorkflowOperationLog(initialWorkflow);
      this.sessionService =
          new CollaborativeWorkflowSessionService(
              sessionId,
              mode,
              operationLog,
              new InMemoryWorkflowEventOutbox(),
              ignored -> {});
      participants.put(owner.actorId(), owner);
    }

    synchronized SessionSnapshot join(OperationActor actor) {
      if (mode == CollaborationMode.PRIVATE_WORKSPACE && !owner.actorId().equals(actor.actorId())) {
        throw new IllegalStateException(
            "Private workspace can only be joined by its owner: " + owner.actorId());
      }
      OperationActor existing = participants.get(actor.actorId());
      if (existing != null && !existing.equals(actor)) {
        throw new IllegalArgumentException(
            "Actor metadata mismatch for already joined actor: " + actor.actorId());
      }
      participants.putIfAbsent(actor.actorId(), actor);
      return snapshot();
    }

    synchronized SessionSnapshot leave(String actorId) {
      if (!participants.containsKey(actorId)) {
        throw new IllegalStateException("Actor is not joined: " + actorId);
      }
      participants.remove(actorId);
      sessionService.clearPresence(actorId);
      return snapshot();
    }

    synchronized Workflow apply(
        CollaborationMode requestedMode, OperationActor actor, WorkflowOperation operation) {
      if (mode != requestedMode) {
        throw new IllegalArgumentException(
            "Requested mode '" + requestedMode + "' does not match session mode '" + mode + "'");
      }
      OperationActor joinedActor = participants.get(actor.actorId());
      if (joinedActor == null) {
        throw new IllegalStateException("Actor is not joined: " + actor.actorId());
      }
      if (!joinedActor.equals(actor)) {
        throw new IllegalArgumentException("Actor metadata mismatch: " + actor.actorId());
      }
      WorkflowOperationEnvelope envelope =
          new WorkflowOperationEnvelope(sessionId, mode, actor, operation, Instant.now());
      return sessionService.applyOperation(envelope);
    }

    synchronized Workflow workflow() {
      return operationLog.currentWorkflow();
    }

    synchronized SessionSnapshot snapshot() {
      List<OperationActor> actors = new ArrayList<>(participants.values());
      actors.sort(Comparator.comparing(OperationActor::actorId));
      return new SessionSnapshot(
          sessionId,
          mode,
          owner,
          createdAt,
          actors,
          operationLog.operations().size(),
          operationLog.currentWorkflow().id());
    }

    synchronized void assertOwner(String actorId) {
      if (!owner.actorId().equals(actorId)) {
        throw new IllegalStateException("Only the session owner may close it: " + owner.actorId());
      }
    }
  }

  /** Immutable transport-neutral session metadata. */
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
