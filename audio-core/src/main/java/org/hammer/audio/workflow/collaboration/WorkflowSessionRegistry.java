package org.hammer.audio.workflow.collaboration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.WorkflowOperationLog;
import org.hammer.audio.workflow.WorkflowValidator;

/** Thread-safe, recoverable collaboration-session aggregate and application service. */
public final class WorkflowSessionRegistry {

  private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();
  private final WorkflowSessionStateStore stateStore;

  public WorkflowSessionRegistry() {
    this(WorkflowSessionStateStore.noOp());
  }

  public WorkflowSessionRegistry(WorkflowSessionStateStore stateStore) {
    this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
    for (WorkflowSessionState restored : stateStore.restore()) {
      SessionEntry restoredEntry =
          new SessionEntry(
              restored.sessionId(),
              restored.mode(),
              restored.owner(),
              restored.createdAt(),
              restored.initialWorkflow(),
              restored.operations(),
              restored.participants(),
              restored.presence(),
              restored.undoEntries(),
              restored.revision(),
              restored.sequence());
      if (!restoredEntry.operationLog.currentWorkflow().equals(restored.workflow())) {
        throw new IllegalStateException(
            "Restored operation replay diverges for session " + restored.sessionId());
      }
      SessionEntry previous = sessions.putIfAbsent(restored.sessionId(), restoredEntry);
      if (previous != null) {
        throw new IllegalStateException("Duplicate restored session: " + restored.sessionId());
      }
    }
  }

  /** Creates a new session and joins its owner. */
  public synchronized SessionSnapshot create(
      String sessionId, CollaborationMode mode, OperationActor owner, Workflow initialWorkflow) {
    String requiredSessionId = requireNotBlank(sessionId, "sessionId");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(initialWorkflow, "initialWorkflow");
    if (sessions.containsKey(requiredSessionId)) {
      throw failure(
          WorkflowSessionException.Code.SESSION_ALREADY_EXISTS,
          requiredSessionId,
          "Session already exists: " + requiredSessionId);
    }
    SessionEntry created =
        new SessionEntry(
            requiredSessionId,
            mode,
            owner,
            Instant.now(),
            initialWorkflow,
            List.of(),
            List.of(owner),
            Map.of(),
            List.of(),
            0L,
            1L);
    WorkflowSessionState next = created.state();
    WorkflowSessionEvent event =
        WorkflowSessionEvent.create(
            requiredSessionId,
            next.sequence(),
            next.revision(),
            WorkflowSessionEvent.Type.SESSION_CREATED,
            owner,
            null,
            next,
            Map.of());
    stateStore.commit(
        new WorkflowSessionStateStore.Transition(
            WorkflowSessionStateStore.Kind.CREATE, null, next, null, event));
    sessions.put(requiredSessionId, created);
    return created.snapshot();
  }

  public SessionSnapshot join(String sessionId, OperationActor actor) {
    return requireSession(sessionId).join(Objects.requireNonNull(actor, "actor"));
  }

  public SessionSnapshot leave(String sessionId, String actorId) {
    return requireSession(sessionId).leave(requireNotBlank(actorId, "actorId"));
  }

  public SessionSnapshot inspect(String sessionId) {
    return requireSession(sessionId).snapshot();
  }

  public Workflow workflow(String sessionId) {
    return requireSession(sessionId).state().workflow();
  }

  public WorkflowSessionState state(String sessionId) {
    return requireSession(sessionId).state();
  }

  public List<WorkflowOperation> operations(String sessionId) {
    return requireSession(sessionId).state().operations();
  }

  public Workflow applyOperation(
      String sessionId, CollaborationMode mode, OperationActor actor, WorkflowOperation operation) {
    SessionEntry entry = requireSession(sessionId);
    return entry.apply(mode, actor, entry.revision(), operation).workflow();
  }

  public MutationResult applyOperation(
      String sessionId,
      CollaborationMode mode,
      OperationActor actor,
      long expectedRevision,
      WorkflowOperation operation) {
    return requireSession(sessionId).apply(mode, actor, expectedRevision, operation);
  }

  public MutationResult updatePresence(
      String sessionId, OperationActor actor, WorkflowPresence presence) {
    return requireSession(sessionId).updatePresence(actor, presence);
  }

  public MutationResult clearPresence(String sessionId, OperationActor actor) {
    return requireSession(sessionId).clearPresence(actor);
  }

  public MutationResult undo(
      String sessionId, OperationActor actor, long expectedRevision, String targetOperationId) {
    return requireSession(sessionId).undo(actor, expectedRevision, targetOperationId);
  }

  public MutationResult redo(String sessionId, OperationActor actor, long expectedRevision) {
    return requireSession(sessionId).redo(actor, expectedRevision);
  }

  /** Explicitly closes a session. Only its owner may close it. */
  public synchronized void close(String sessionId, String requestedByActorId) {
    String requiredSessionId = requireNotBlank(sessionId, "sessionId");
    String actorId = requireNotBlank(requestedByActorId, "requestedByActorId");
    SessionEntry entry = requireSession(requiredSessionId);
    entry.close(actorId);
    sessions.remove(requiredSessionId, entry);
  }

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
      throw failure(
          WorkflowSessionException.Code.SESSION_NOT_FOUND,
          requiredSessionId,
          "Unknown session: " + requiredSessionId);
    }
    return entry;
  }

  private final class SessionEntry {
    private final String sessionId;
    private final CollaborationMode mode;
    private final OperationActor owner;
    private final Instant createdAt;
    private final Workflow initialWorkflow;
    private final WorkflowOperationLog operationLog;
    private final WorkflowValidator validator = new WorkflowValidator();
    private final Map<String, OperationActor> participants = new LinkedHashMap<>();
    private final Map<String, WorkflowPresence> presence = new LinkedHashMap<>();
    private final List<WorkflowUndoEntry> undoEntries = new ArrayList<>();
    private long revision;
    private long sequence;

    private SessionEntry(
        String sessionId,
        CollaborationMode mode,
        OperationActor owner,
        Instant createdAt,
        Workflow initialWorkflow,
        List<WorkflowOperation> operations,
        List<OperationActor> restoredParticipants,
        Map<String, WorkflowPresence> restoredPresence,
        List<WorkflowUndoEntry> restoredUndoEntries,
        long revision,
        long sequence) {
      this.sessionId = sessionId;
      this.mode = mode;
      this.owner = owner;
      this.createdAt = createdAt;
      this.initialWorkflow = initialWorkflow;
      this.operationLog = new WorkflowOperationLog(initialWorkflow);
      for (WorkflowOperation operation : operations) {
        operationLog.apply(operation);
      }
      restoredParticipants.forEach(actor -> participants.put(actor.actorId(), actor));
      presence.putAll(restoredPresence);
      undoEntries.addAll(restoredUndoEntries);
      this.revision = revision;
      this.sequence = sequence;
    }

    static SessionEntry create(
        String sessionId, CollaborationMode mode, OperationActor owner, Workflow initialWorkflow) {
      return WorkflowSessionRegistry.this
      .new SessionEntry(
          sessionId,
          mode,
          owner,
          Instant.now(),
          initialWorkflow,
          List.of(),
          List.of(owner),
          Map.of(),
          List.of(),
          0L,
          1L);
    }

    static SessionEntry restore(WorkflowSessionState state) {
      SessionEntry entry =
          WorkflowSessionRegistry.this
          .new SessionEntry(
              state.sessionId(),
              state.mode(),
              state.owner(),
              state.createdAt(),
              state.initialWorkflow(),
              state.operations(),
              state.participants(),
              state.presence(),
              state.undoEntries(),
              state.revision(),
              state.sequence());
      if (!entry.operationLog.currentWorkflow().equals(state.workflow())) {
        throw new IllegalStateException(
            "Restored operation replay diverges for session " + state.sessionId());
      }
      return entry;
    }

    synchronized long revision() {
      return revision;
    }

    synchronized SessionSnapshot join(OperationActor actor) {
      if (mode == CollaborationMode.PRIVATE_WORKSPACE && !owner.actorId().equals(actor.actorId())) {
        throw failure(
            WorkflowSessionException.Code.PRIVATE_WORKSPACE_ACCESS_DENIED,
            sessionId,
            "Private workspace can only be joined by its owner: " + owner.actorId());
      }
      OperationActor existing = participants.get(actor.actorId());
      if (existing != null && !existing.equals(actor)) {
        throw failure(
            WorkflowSessionException.Code.ACTOR_METADATA_MISMATCH,
            sessionId,
            "Actor metadata mismatch for already joined actor: " + actor.actorId());
      }
      if (existing != null) {
        return snapshot();
      }
      WorkflowSessionState previous = state();
      participants.put(actor.actorId(), actor);
      sequence++;
      WorkflowSessionState next = state();
      WorkflowSessionEvent event =
          event(WorkflowSessionEvent.Type.ACTOR_JOINED, actor, null, next, Map.of());
      try {
        stateStore.commit(
            new WorkflowSessionStateStore.Transition(
                WorkflowSessionStateStore.Kind.MEMBERSHIP, previous, next, null, event));
      } catch (RuntimeException ex) {
        participants.remove(actor.actorId());
        sequence--;
        throw ex;
      }
      return snapshot();
    }

    synchronized SessionSnapshot leave(String actorId) {
      OperationActor actor = participants.get(actorId);
      if (actor == null) {
        throw failure(
            WorkflowSessionException.Code.ACTOR_NOT_JOINED,
            sessionId,
            "Actor is not joined: " + actorId);
      }
      WorkflowSessionState previous = state();
      WorkflowPresence oldPresence = presence.remove(actorId);
      participants.remove(actorId);
      sequence++;
      WorkflowSessionState next = state();
      WorkflowSessionEvent event =
          event(WorkflowSessionEvent.Type.ACTOR_LEFT, actor, null, next, Map.of());
      try {
        stateStore.commit(
            new WorkflowSessionStateStore.Transition(
                WorkflowSessionStateStore.Kind.MEMBERSHIP, previous, next, null, event));
      } catch (RuntimeException ex) {
        participants.put(actorId, actor);
        if (oldPresence != null) {
          presence.put(actorId, oldPresence);
        }
        sequence--;
        throw ex;
      }
      return snapshot();
    }

    synchronized MutationResult apply(
        CollaborationMode requestedMode,
        OperationActor actor,
        long expectedRevision,
        WorkflowOperation operation) {
      verifyActorAndRevision(requestedMode, actor, expectedRevision, operation);
      List<WorkflowUndoEntry> retained =
          undoEntries.stream()
              .filter(entry -> entry.redone() || !entry.requestedByActor().equals(actor.actorId()))
              .toList();
      return applySemantic(
          actor,
          operation,
          WorkflowSessionEvent.Type.OPERATION_ACCEPTED,
          WorkflowSessionStateStore.Kind.OPERATION,
          retained,
          Map.of());
    }

    synchronized MutationResult undo(
        OperationActor actor, long expectedRevision, String targetOperationId) {
      requireJoined(actor);
      requireRevision(expectedRevision);
      WorkflowOperation target;
      UndoScope scope = mode.undoScope();
      if (scope == UndoScope.SHARED) {
        if (targetOperationId == null || targetOperationId.isBlank()) {
          throw failure(
              WorkflowSessionException.Code.NOTHING_TO_UNDO,
              sessionId,
              "Shared undo requires an explicit target operation id");
        }
        target = findOperation(targetOperationId);
      } else {
        target = latestPersonalUndoTarget(actor.actorId());
        assertPersonalUndoSafe(actor.actorId(), target);
      }
      WorkflowOperation inverse =
          target
              .inverseOperation()
              .orElseThrow(
                  () ->
                      failure(
                          WorkflowSessionException.Code.INVALID_WORKFLOW_OPERATION,
                          sessionId,
                          "Operation has no semantic inverse: "
                              + target.getClass().getSimpleName()));
      List<WorkflowUndoEntry> nextUndo = new ArrayList<>(undoEntries);
      nextUndo.add(
          new WorkflowUndoEntry(
              actor.actorId(),
              scope,
              target.operationId(),
              inverse.operationId(),
              null,
              false,
              Instant.now()));
      return applySemantic(
          actor,
          inverse,
          WorkflowSessionEvent.Type.UNDO_ACCEPTED,
          WorkflowSessionStateStore.Kind.UNDO,
          nextUndo,
          Map.of("targetOperationId", target.operationId(), "targetActorId", target.author()));
    }

    synchronized MutationResult redo(OperationActor actor, long expectedRevision) {
      requireJoined(actor);
      requireRevision(expectedRevision);
      int undoIndex = -1;
      for (int i = undoEntries.size() - 1; i >= 0; i--) {
        WorkflowUndoEntry candidate = undoEntries.get(i);
        if (!candidate.redone() && candidate.requestedByActor().equals(actor.actorId())) {
          undoIndex = i;
          break;
        }
      }
      if (undoIndex < 0) {
        throw failure(
            WorkflowSessionException.Code.NOTHING_TO_REDO,
            sessionId,
            "No semantic undo is available to redo for actor: " + actor.actorId());
      }
      WorkflowUndoEntry undo = undoEntries.get(undoIndex);
      WorkflowOperation inverse = findOperation(undo.inverseOperationId());
      WorkflowOperation redo =
          inverse
              .inverseOperation()
              .orElseThrow(
                  () ->
                      failure(
                          WorkflowSessionException.Code.INVALID_WORKFLOW_OPERATION,
                          sessionId,
                          "Undo operation has no semantic inverse"));
      List<WorkflowUndoEntry> nextUndo = new ArrayList<>(undoEntries);
      nextUndo.set(undoIndex, undo.markRedone(redo.operationId()));
      return applySemantic(
          actor,
          redo,
          WorkflowSessionEvent.Type.REDO_ACCEPTED,
          WorkflowSessionStateStore.Kind.REDO,
          nextUndo,
          Map.of("targetOperationId", undo.targetOperationId()));
    }

    synchronized MutationResult updatePresence(OperationActor actor, WorkflowPresence newPresence) {
      requireJoined(actor);
      Objects.requireNonNull(newPresence, "presence");
      if (!actor.actorId().equals(newPresence.actorId())) {
        throw failure(
            WorkflowSessionException.Code.ACTOR_METADATA_MISMATCH,
            sessionId,
            "Presence actorId does not match request actor");
      }
      WorkflowSessionState previous = state();
      WorkflowPresence old = presence.put(actor.actorId(), newPresence);
      sequence++;
      WorkflowSessionState next = state();
      WorkflowSessionEvent event =
          event(WorkflowSessionEvent.Type.PRESENCE_UPDATED, actor, null, next, Map.of());
      try {
        stateStore.commit(
            new WorkflowSessionStateStore.Transition(
                WorkflowSessionStateStore.Kind.PRESENCE, previous, next, null, event));
      } catch (RuntimeException ex) {
        if (old == null) {
          presence.remove(actor.actorId());
        } else {
          presence.put(actor.actorId(), old);
        }
        sequence--;
        throw ex;
      }
      return new MutationResult(snapshot(), operationLog.currentWorkflow(), event);
    }

    synchronized MutationResult clearPresence(OperationActor actor) {
      requireJoined(actor);
      WorkflowSessionState previous = state();
      WorkflowPresence old = presence.remove(actor.actorId());
      if (old == null) {
        return new MutationResult(snapshot(), operationLog.currentWorkflow(), null);
      }
      sequence++;
      WorkflowSessionState next = state();
      WorkflowSessionEvent event =
          event(WorkflowSessionEvent.Type.PRESENCE_CLEARED, actor, null, next, Map.of());
      try {
        stateStore.commit(
            new WorkflowSessionStateStore.Transition(
                WorkflowSessionStateStore.Kind.PRESENCE, previous, next, null, event));
      } catch (RuntimeException ex) {
        presence.put(actor.actorId(), old);
        sequence--;
        throw ex;
      }
      return new MutationResult(snapshot(), operationLog.currentWorkflow(), event);
    }

    synchronized void close(String actorId) {
      if (!owner.actorId().equals(actorId)) {
        throw failure(
            WorkflowSessionException.Code.SESSION_CLOSE_FORBIDDEN,
            sessionId,
            "Only the session owner may close it: " + owner.actorId());
      }
      WorkflowSessionState previous = state();
      sequence++;
      WorkflowSessionState next = state();
      WorkflowSessionEvent event =
          event(WorkflowSessionEvent.Type.SESSION_CLOSED, owner, null, next, Map.of());
      try {
        stateStore.commit(
            new WorkflowSessionStateStore.Transition(
                WorkflowSessionStateStore.Kind.CLOSE, previous, next, null, event));
      } catch (RuntimeException ex) {
        sequence--;
        throw ex;
      }
    }

    private MutationResult applySemantic(
        OperationActor actor,
        WorkflowOperation operation,
        WorkflowSessionEvent.Type eventType,
        WorkflowSessionStateStore.Kind kind,
        List<WorkflowUndoEntry> nextUndoEntries,
        Map<String, String> details) {
      Workflow candidate;
      try {
        candidate = operation.apply(operationLog.currentWorkflow());
      } catch (IllegalArgumentException | IllegalStateException ex) {
        throw failure(
            WorkflowSessionException.Code.INVALID_WORKFLOW_OPERATION,
            sessionId,
            ex.getMessage() == null ? "Workflow operation failed" : ex.getMessage());
      }
      List<String> violations = validator.validate(candidate);
      if (!violations.isEmpty()) {
        throw failure(
            WorkflowSessionException.Code.INVALID_WORKFLOW_OPERATION,
            sessionId,
            String.join("; ", violations));
      }
      WorkflowSessionState previous = state();
      List<WorkflowOperation> nextOperations = new ArrayList<>(previous.operations());
      nextOperations.add(operation);
      long nextRevision = revision + 1;
      long nextSequence = sequence + 1;
      WorkflowSessionState next =
          new WorkflowSessionState(
              sessionId,
              mode,
              owner,
              createdAt,
              initialWorkflow,
              candidate,
              new ArrayList<>(participants.values()),
              nextOperations,
              presence,
              nextUndoEntries,
              nextRevision,
              nextSequence);
      WorkflowSessionEvent event =
          WorkflowSessionEvent.create(
              sessionId,
              nextSequence,
              nextRevision,
              eventType,
              actor,
              operation.operationId(),
              next,
              details);
      stateStore.commit(
          new WorkflowSessionStateStore.Transition(kind, previous, next, operation, event));
      operationLog.apply(operation);
      undoEntries.clear();
      undoEntries.addAll(nextUndoEntries);
      revision = nextRevision;
      sequence = nextSequence;
      return new MutationResult(snapshot(), operationLog.currentWorkflow(), event);
    }

    private void verifyActorAndRevision(
        CollaborationMode requestedMode,
        OperationActor actor,
        long expectedRevision,
        WorkflowOperation operation) {
      Objects.requireNonNull(requestedMode, "mode");
      Objects.requireNonNull(actor, "actor");
      Objects.requireNonNull(operation, "operation");
      if (mode != requestedMode) {
        throw failure(
            WorkflowSessionException.Code.SESSION_MODE_MISMATCH,
            sessionId,
            "Requested mode '" + requestedMode + "' does not match session mode '" + mode + "'");
      }
      requireJoined(actor);
      requireRevision(expectedRevision);
      if (!operation.author().equals(actor.actorId())) {
        throw failure(
            WorkflowSessionException.Code.INVALID_OPERATION_AUTHOR,
            sessionId,
            "operation author '"
                + operation.author()
                + "' does not match actor '"
                + actor.actorId()
                + "'");
      }
    }

    private void requireJoined(OperationActor actor) {
      Objects.requireNonNull(actor, "actor");
      OperationActor joined = participants.get(actor.actorId());
      if (joined == null) {
        throw failure(
            WorkflowSessionException.Code.ACTOR_NOT_JOINED,
            sessionId,
            "Actor is not joined: " + actor.actorId());
      }
      if (!joined.equals(actor)) {
        throw failure(
            WorkflowSessionException.Code.ACTOR_METADATA_MISMATCH,
            sessionId,
            "Actor metadata mismatch: " + actor.actorId());
      }
    }

    private void requireRevision(long expectedRevision) {
      if (expectedRevision != revision) {
        throw failure(
            WorkflowSessionException.Code.REVISION_CONFLICT,
            sessionId,
            "Expected revision " + expectedRevision + " but current revision is " + revision);
      }
    }

    private WorkflowOperation latestPersonalUndoTarget(String actorId) {
      Set<String> unavailable = new HashSet<>();
      for (WorkflowUndoEntry entry : undoEntries) {
        if (!entry.redone()) {
          unavailable.add(entry.targetOperationId());
          unavailable.add(entry.inverseOperationId());
        }
      }
      List<WorkflowOperation> operations = operationLog.operations();
      for (int i = operations.size() - 1; i >= 0; i--) {
        WorkflowOperation operation = operations.get(i);
        if (operation.author().equals(actorId) && !unavailable.contains(operation.operationId())) {
          return operation;
        }
      }
      throw failure(
          WorkflowSessionException.Code.NOTHING_TO_UNDO,
          sessionId,
          "No operation is available to undo for actor: " + actorId);
    }

    private WorkflowOperation findOperation(String operationId) {
      for (WorkflowOperation operation : operationLog.operations()) {
        if (operation.operationId().equals(operationId)) {
          return operation;
        }
      }
      throw failure(
          WorkflowSessionException.Code.NOTHING_TO_UNDO,
          sessionId,
          "Operation not found: " + operationId);
    }

    private void assertPersonalUndoSafe(String actorId, WorkflowOperation target) {
      List<WorkflowOperation> history = operationLog.operations();
      int targetIndex = -1;
      for (int i = history.size() - 1; i >= 0; i--) {
        if (history.get(i).operationId().equals(target.operationId())) {
          targetIndex = i;
          break;
        }
      }
      Set<String> affected = new HashSet<>(target.affectedObjectIds());
      for (int i = targetIndex + 1; i < history.size(); i++) {
        WorkflowOperation later = history.get(i);
        if (!later.author().equals(actorId)
            && later.affectedObjectIds().stream().anyMatch(affected::contains)) {
          throw failure(
              WorkflowSessionException.Code.REVISION_CONFLICT,
              sessionId,
              "Personal undo is blocked by later operation "
                  + later.operationId()
                  + " from actor "
                  + later.author());
        }
      }
    }

    synchronized WorkflowSessionState state() {
      return new WorkflowSessionState(
          sessionId,
          mode,
          owner,
          createdAt,
          initialWorkflow,
          operationLog.currentWorkflow(),
          new ArrayList<>(participants.values()),
          operationLog.operations(),
          presence,
          undoEntries,
          revision,
          sequence);
    }

    synchronized SessionSnapshot snapshot() {
      return new SessionSnapshot(
          sessionId,
          mode,
          owner,
          createdAt,
          new ArrayList<>(participants.values()),
          operationLog.operations().size(),
          operationLog.currentWorkflow().id(),
          revision,
          sequence);
    }

    private WorkflowSessionEvent event(
        WorkflowSessionEvent.Type type,
        OperationActor actor,
        String operationId,
        WorkflowSessionState next,
        Map<String, String> details) {
      return WorkflowSessionEvent.create(
          sessionId, next.sequence(), next.revision(), type, actor, operationId, next, details);
    }
  }

  /** Result returned by mutation endpoints. */
  public record MutationResult(
      SessionSnapshot session, Workflow workflow, WorkflowSessionEvent event) {
    public MutationResult {
      Objects.requireNonNull(session, "session");
      Objects.requireNonNull(workflow, "workflow");
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
      String workflowId,
      long revision,
      long sequence) {

    public SessionSnapshot {
      requireNotBlank(sessionId, "sessionId");
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(createdAt, "createdAt");
      participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
      if (operationCount < 0 || revision < 0 || sequence < 0) {
        throw new IllegalArgumentException("counts, revision and sequence must be >= 0");
      }
      requireNotBlank(workflowId, "workflowId");
    }
  }

  private static WorkflowSessionException failure(
      WorkflowSessionException.Code code, String sessionId, String message) {
    return new WorkflowSessionException(code, sessionId, message);
  }

  private static String requireNotBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
