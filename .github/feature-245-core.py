from pathlib import Path
from textwrap import dedent

ROOT = Path(__file__).resolve().parents[1]


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(dedent(content).lstrip(), encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise SystemExit(f"Unexpected source in {path}: expected one occurrence of {old!r}")
    target.write_text(text.replace(old, new), encoding="utf-8")


write(
    "audio-core/src/main/java/org/hammer/audio/workflow/collaboration/WorkflowSessionState.java",
    r'''
    package org.hammer.audio.workflow.collaboration;

    import java.time.Instant;
    import java.util.List;
    import java.util.Objects;
    import org.hammer.audio.workflow.Workflow;
    import org.hammer.audio.workflow.WorkflowOperation;

    /** Complete framework-independent state required to recover one collaboration session. */
    public record WorkflowSessionState(
        String sessionId,
        CollaborationMode mode,
        OperationActor owner,
        Instant createdAt,
        Workflow initialWorkflow,
        Workflow workflow,
        List<OperationActor> participants,
        List<WorkflowOperation> operations,
        long revision,
        long sequence,
        boolean closed) {

      /** Validates and defensively copies the recoverable session state. */
      public WorkflowSessionState {
        sessionId = requireNotBlank(sessionId, "sessionId");
        mode = Objects.requireNonNull(mode, "mode");
        owner = Objects.requireNonNull(owner, "owner");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        initialWorkflow = Objects.requireNonNull(initialWorkflow, "initialWorkflow");
        workflow = Objects.requireNonNull(workflow, "workflow");
        participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
        operations = List.copyOf(Objects.requireNonNull(operations, "operations"));
        if (revision < 0 || sequence < 0) {
          throw new IllegalArgumentException("revision and sequence must be >= 0");
        }
      }

      private static String requireNotBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
          throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
      }
    }
    ''',
)

write(
    "audio-core/src/main/java/org/hammer/audio/workflow/collaboration/WorkflowSessionStateStore.java",
    r'''
    package org.hammer.audio.workflow.collaboration;

    import java.time.Instant;
    import java.util.List;
    import java.util.Objects;
    import org.hammer.audio.workflow.WorkflowOperation;

    /** Durable session-state and transactional-outbox boundary used by the collaboration aggregate. */
    public interface WorkflowSessionStateStore {

      /** Restores all active sessions at application startup. */
      List<WorkflowSessionState> restoreActive();

      /** Atomically persists one state transition, optional operation and all outbound events. */
      void commit(Transition transition);

      /** Returns due unpublished outbox records in stable creation order. */
      List<OutboxRecord> pendingOutbox(int limit, Instant now);

      /** Marks an outbox event as published. */
      void markPublished(String eventId, Instant publishedAt);

      /** Records a failed publication attempt and its next retry instant. */
      void markAttempt(String eventId, Instant nextAttemptAt, String errorMessage);

      /** State transition categories kept independent of any persistence framework. */
      enum Kind {
        CREATE,
        MEMBERSHIP,
        OPERATION,
        PRESENCE,
        CLOSE
      }

      /**
       * One optimistic aggregate transition.
       *
       * @param kind transition category
       * @param previousState state expected in durable storage; {@code null} only for creation
       * @param nextState complete state after the transition
       * @param operation accepted semantic operation, or {@code null} for non-semantic transitions
       * @param events ordered committed events inserted into the outbox
       */
      record Transition(
          Kind kind,
          WorkflowSessionState previousState,
          WorkflowSessionState nextState,
          WorkflowOperation operation,
          List<WorkflowSessionEvent> events) {

        /** Validates transition invariants before an adapter starts a transaction. */
        public Transition {
          kind = Objects.requireNonNull(kind, "kind");
          nextState = Objects.requireNonNull(nextState, "nextState");
          events = List.copyOf(Objects.requireNonNull(events, "events"));
          if (kind == Kind.CREATE && previousState != null) {
            throw new IllegalArgumentException("CREATE must not have a previous state");
          }
          if (kind != Kind.CREATE && previousState == null) {
            throw new IllegalArgumentException(kind + " requires a previous state");
          }
          if (events.isEmpty()) {
            throw new IllegalArgumentException("events must not be empty");
          }
          WorkflowSessionEvent last = events.getLast();
          if (!last.sessionId().equals(nextState.sessionId())
              || last.sequence() != nextState.sequence()
              || last.revision() != nextState.revision()) {
            throw new IllegalArgumentException("last event must describe the complete next state");
          }
          for (WorkflowSessionEvent event : events) {
            if (!event.sessionId().equals(nextState.sessionId())) {
              throw new IllegalArgumentException("all events must belong to the transitioned session");
            }
          }
        }
      }

      /**
       * One durable unpublished event.
       *
       * @param eventId stable idempotency key
       * @param event transport-neutral event payload
       * @param attemptCount number of previous failed attempts
       * @param nextAttemptAt earliest retry instant
       * @param lastError last publication error, or {@code null}
       */
      record OutboxRecord(
          String eventId,
          WorkflowSessionEvent event,
          int attemptCount,
          Instant nextAttemptAt,
          String lastError) {

        /** Validates the transport-neutral outbox record. */
        public OutboxRecord {
          eventId = requireNotBlank(eventId, "eventId");
          event = Objects.requireNonNull(event, "event");
          if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount must be >= 0");
          }
          nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        }
      }

      /** Returns an in-memory/demo adapter that deliberately stores no durable state. */
      static WorkflowSessionStateStore noOp() {
        return new WorkflowSessionStateStore() {
          @Override
          public List<WorkflowSessionState> restoreActive() {
            return List.of();
          }

          @Override
          public void commit(Transition transition) {
            Objects.requireNonNull(transition, "transition");
          }

          @Override
          public List<OutboxRecord> pendingOutbox(int limit, Instant now) {
            requirePositive(limit, "limit");
            Objects.requireNonNull(now, "now");
            return List.of();
          }

          @Override
          public void markPublished(String eventId, Instant publishedAt) {
            requireNotBlank(eventId, "eventId");
            Objects.requireNonNull(publishedAt, "publishedAt");
          }

          @Override
          public void markAttempt(String eventId, Instant nextAttemptAt, String errorMessage) {
            requireNotBlank(eventId, "eventId");
            Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
          }
        };
      }

      private static int requirePositive(int value, String field) {
        if (value < 1) {
          throw new IllegalArgumentException(field + " must be >= 1");
        }
        return value;
      }

      private static String requireNotBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
          throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
      }
    }
    ''',
)

write(
    "audio-core/src/main/java/org/hammer/audio/workflow/collaboration/WorkflowSessionEventPublisher.java",
    r'''
    package org.hammer.audio.workflow.collaboration;

    import java.util.Objects;

    /** Replaceable external transport boundary consumed by the durable outbox dispatcher. */
    @FunctionalInterface
    public interface WorkflowSessionEventPublisher {

      /** Publishes one already committed event. Implementations must be idempotent by event id. */
      void publish(WorkflowSessionEvent event);

      /** Returns a publisher used when no external broker adapter is configured. */
      static WorkflowSessionEventPublisher noOp() {
        return event -> Objects.requireNonNull(event, "event");
      }
    }
    ''',
)

write(
    "audio-core/src/main/java/org/hammer/audio/workflow/collaboration/WorkflowSessionRegistry.java",
    r'''
    package org.hammer.audio.workflow.collaboration;

    import java.time.Instant;
    import java.util.ArrayList;
    import java.util.Comparator;
    import java.util.LinkedHashMap;
    import java.util.List;
    import java.util.Map;
    import java.util.Objects;
    import java.util.concurrent.ConcurrentHashMap;
    import java.util.concurrent.locks.ReentrantLock;
    import org.hammer.audio.workflow.Workflow;
    import org.hammer.audio.workflow.WorkflowOperation;
    import org.hammer.audio.workflow.WorkflowOperationLog;
    import org.hammer.audio.workflow.WorkflowValidator;
    import org.hammer.audio.workflow.collaboration.WorkflowSessionException.Code;

    /** Thread-safe recoverable collaboration-session aggregate and application service. */
    public final class WorkflowSessionRegistry {

      private static final String SESSION_ID_FIELD = "sessionId";

      private final Map<String, SessionEntry> sessionEntries = new ConcurrentHashMap<>();
      private final WorkflowSessionEventHub sessionEventHub;
      private final WorkflowSessionStateStore stateStore;

      /** Creates a registry with in-memory event streaming and no durable state. */
      public WorkflowSessionRegistry() {
        this(new WorkflowSessionEventHub(), WorkflowSessionStateStore.noOp());
      }

      /** Creates a registry publishing to the supplied hub without durable state. */
      public WorkflowSessionRegistry(WorkflowSessionEventHub eventHub) {
        this(eventHub, WorkflowSessionStateStore.noOp());
      }

      /** Creates a registry backed by the supplied event hub and durable state adapter. */
      public WorkflowSessionRegistry(
          WorkflowSessionEventHub eventHub, WorkflowSessionStateStore stateStore) {
        this.sessionEventHub = Objects.requireNonNull(eventHub, "eventHub");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        restoreSessions();
      }

      /** Returns the transport-neutral event hub used by this registry. */
      public WorkflowSessionEventHub eventHub() {
        return sessionEventHub;
      }

      /** Creates a new session, persists it and joins its owner. */
      public synchronized SessionSnapshot create(
          String sessionId, CollaborationMode mode, OperationActor owner, Workflow initialWorkflow) {
        String requiredSessionId = requireNotBlank(sessionId, SESSION_ID_FIELD);
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(initialWorkflow, "initialWorkflow");
        if (sessionEntries.containsKey(requiredSessionId)) {
          throw error(
              Code.SESSION_ALREADY_EXISTS,
              requiredSessionId,
              "Session already exists: " + requiredSessionId);
        }

        SessionEntry created = new SessionEntry(requiredSessionId, mode, owner, Instant.now(), initialWorkflow);
        created.sequence = 2L;
        WorkflowSessionState next = created.stateLocked(false);
        WorkflowSessionEvent createdEvent =
            event(
                requiredSessionId,
                1L,
                0L,
                WorkflowSessionEvent.Type.SESSION_CREATED,
                owner,
                null,
                initialWorkflow,
                Map.of(),
                created.createdAt);
        WorkflowSessionEvent ownerPresence =
            event(
                requiredSessionId,
                2L,
                0L,
                WorkflowSessionEvent.Type.PRESENCE_JOINED,
                owner,
                null,
                null,
                Map.of(),
                created.createdAt);
        try {
          stateStore.commit(
              new WorkflowSessionStateStore.Transition(
                  WorkflowSessionStateStore.Kind.CREATE,
                  null,
                  next,
                  null,
                  List.of(createdEvent, ownerPresence)));
        } catch (RuntimeException ex) {
          created.sequence = 0L;
          throw ex;
        }
        sessionEntries.put(requiredSessionId, created);
        sessionEventHub.restoreSession(requiredSessionId, initialWorkflow, 0L, 0L);
        sessionEventHub.publishCommitted(createdEvent);
        sessionEventHub.publishCommitted(ownerPresence);
        return created.snapshot();
      }

      /** Joins an existing session. Duplicate joins with identical metadata are idempotent. */
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

      /** Returns the complete framework-independent recoverable session state. */
      public WorkflowSessionState state(String sessionId) {
        return requireSession(sessionId).state();
      }

      /** Applies an operation using the current revision for backward-compatible in-process callers. */
      public Workflow applyOperation(
          String sessionId, CollaborationMode mode, OperationActor actor, WorkflowOperation operation) {
        return requireSession(sessionId).apply(mode, actor, operation);
      }

      /** Applies an operation only when the caller's expected semantic revision still matches. */
      public Workflow applyOperation(
          String sessionId,
          CollaborationMode mode,
          OperationActor actor,
          long expectedRevision,
          WorkflowOperation operation) {
        return requireSession(sessionId).apply(mode, actor, expectedRevision, operation);
      }

      /** Persists and broadcasts non-semantic presence for a joined actor. */
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
      public synchronized void close(String sessionId, String requestedByActorId) {
        String requiredSessionId = requireNotBlank(sessionId, SESSION_ID_FIELD);
        SessionEntry entry = requireSession(requiredSessionId);
        entry.close(requireNotBlank(requestedByActorId, "requestedByActorId"));
        sessionEntries.remove(requiredSessionId, entry);
      }

      /** Returns all current sessions in stable identifier order. */
      public List<SessionSnapshot> sessions() {
        return sessionEntries.values().stream()
            .map(SessionEntry::snapshot)
            .sorted(Comparator.comparing(SessionSnapshot::sessionId))
            .toList();
      }

      private void restoreSessions() {
        for (WorkflowSessionState restored : stateStore.restoreActive()) {
          if (restored.closed()) {
            continue;
          }
          SessionEntry entry = SessionEntry.restore(restored);
          SessionEntry previous = sessionEntries.putIfAbsent(restored.sessionId(), entry);
          if (previous != null) {
            throw new IllegalStateException("Duplicate restored session: " + restored.sessionId());
          }
          sessionEventHub.restoreSession(
              restored.sessionId(), restored.workflow(), restored.sequence(), restored.revision());
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

      private static WorkflowSessionEvent event(
          String sessionId,
          long sequence,
          long revision,
          WorkflowSessionEvent.Type type,
          OperationActor actor,
          String operationId,
          Workflow workflow,
          Map<String, String> attributes,
          Instant occurredAt) {
        return new WorkflowSessionEvent(
            sessionId + ":" + sequence,
            sessionId,
            sequence,
            revision,
            occurredAt,
            type,
            actor,
            operationId,
            workflow,
            attributes);
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

      private final class SessionEntry {
        private final String sessionId;
        private final CollaborationMode mode;
        private final OperationActor owner;
        private final Instant createdAt;
        private final Workflow initialWorkflow;
        private final WorkflowOperationLog operationLog;
        private final WorkflowValidator validator = new WorkflowValidator();
        private final Map<String, OperationActor> participants = new LinkedHashMap<>();
        private final Map<String, PresenceState> presenceByActor = new LinkedHashMap<>();
        private final Map<String, WorkflowOperation> operationsById = new LinkedHashMap<>();
        private final ReentrantLock lock = new ReentrantLock();
        private long revision;
        private long sequence;
        private boolean closed;

        SessionEntry(
            String sessionId,
            CollaborationMode mode,
            OperationActor owner,
            Instant createdAt,
            Workflow initialWorkflow) {
          this.sessionId = sessionId;
          this.mode = mode;
          this.owner = owner;
          this.createdAt = createdAt;
          this.initialWorkflow = initialWorkflow;
          this.operationLog = new WorkflowOperationLog(initialWorkflow);
          participants.put(owner.actorId(), owner);
        }

        static SessionEntry restore(WorkflowSessionState state) {
          SessionEntry entry =
              WorkflowSessionRegistry.this
              .new SessionEntry(
                  state.sessionId(),
                  state.mode(),
                  state.owner(),
                  state.createdAt(),
                  state.initialWorkflow());
          entry.participants.clear();
          state.participants().forEach(actor -> entry.participants.put(actor.actorId(), actor));
          for (WorkflowOperation operation : state.operations()) {
            entry.operationLog.apply(operation);
            entry.operationsById.put(operation.operationId(), operation);
          }
          if (!entry.operationLog.currentWorkflow().equals(state.workflow())) {
            throw new IllegalStateException(
                "Restored operation replay diverges for session " + state.sessionId());
          }
          entry.revision = state.revision();
          entry.sequence = state.sequence();
          entry.closed = state.closed();
          return entry;
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
            if (existing != null) {
              return snapshotLocked();
            }
            WorkflowSessionState previous = stateLocked(false);
            long nextSequence = sequence + 1L;
            List<OperationActor> nextParticipants = new ArrayList<>(participants.values());
            nextParticipants.add(actor);
            WorkflowSessionState next = stateWith(nextParticipants, revision, nextSequence, false);
            WorkflowSessionEvent joined =
                event(
                    sessionId,
                    nextSequence,
                    revision,
                    WorkflowSessionEvent.Type.PRESENCE_JOINED,
                    actor,
                    null,
                    null,
                    Map.of(),
                    Instant.now());
            stateStore.commit(
                new WorkflowSessionStateStore.Transition(
                    WorkflowSessionStateStore.Kind.MEMBERSHIP,
                    previous,
                    next,
                    null,
                    List.of(joined)));
            participants.put(actor.actorId(), actor);
            sequence = nextSequence;
            sessionEventHub.publishCommitted(joined);
            return snapshotLocked();
          } finally {
            lock.unlock();
          }
        }

        SessionSnapshot leave(String actorId) {
          lock.lock();
          try {
            requireOpen();
            OperationActor actor = participants.get(actorId);
            if (actor == null) {
              throw error(Code.ACTOR_NOT_JOINED, sessionId, "Actor is not joined: " + actorId);
            }
            WorkflowSessionState previous = stateLocked(false);
            long nextSequence = sequence + 1L;
            List<OperationActor> nextParticipants =
                participants.values().stream()
                    .filter(candidate -> !candidate.actorId().equals(actorId))
                    .toList();
            WorkflowSessionState next = stateWith(nextParticipants, revision, nextSequence, false);
            WorkflowSessionEvent left =
                event(
                    sessionId,
                    nextSequence,
                    revision,
                    WorkflowSessionEvent.Type.PRESENCE_LEFT,
                    actor,
                    null,
                    null,
                    Map.of(),
                    Instant.now());
            stateStore.commit(
                new WorkflowSessionStateStore.Transition(
                    WorkflowSessionStateStore.Kind.MEMBERSHIP,
                    previous,
                    next,
                    null,
                    List.of(left)));
            participants.remove(actorId);
            presenceByActor.remove(actorId);
            sequence = nextSequence;
            sessionEventHub.publishCommitted(left);
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

        private Workflow applyLocked(
            CollaborationMode requestedMode,
            OperationActor actor,
            long expectedRevision,
            WorkflowOperation operation) {
          requireOpen();
          verifyActorAndMode(requestedMode, actor, operation);
          WorkflowOperation previousOperation = operationsById.get(operation.operationId());
          if (previousOperation != null) {
            if (sameSemanticOperation(previousOperation, operation)) {
              return operationLog.currentWorkflow();
            }
            throw error(
                Code.DUPLICATE_OPERATION_ID,
                sessionId,
                "Operation id is already associated with different content: "
                    + operation.operationId());
          }
          requireRevision(expectedRevision);
          Workflow candidate;
          try {
            candidate = operation.apply(operationLog.currentWorkflow());
          } catch (IllegalArgumentException | IllegalStateException ex) {
            throw error(
                Code.INVALID_WORKFLOW_OPERATION,
                sessionId,
                ex.getMessage() == null ? "Workflow operation failed" : ex.getMessage());
          }
          List<String> violations = validator.validate(candidate);
          if (!violations.isEmpty()) {
            throw error(Code.INVALID_WORKFLOW_OPERATION, sessionId, String.join("; ", violations));
          }
          WorkflowSessionState previous = stateLocked(false);
          long nextRevision = revision + 1L;
          long nextSequence = sequence + 1L;
          List<WorkflowOperation> nextOperations = new ArrayList<>(operationLog.operations());
          nextOperations.add(operation);
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
                  nextRevision,
                  nextSequence,
                  false);
          WorkflowSessionEvent accepted =
              event(
                  sessionId,
                  nextSequence,
                  nextRevision,
                  WorkflowSessionEvent.Type.OPERATION_ACCEPTED,
                  actor,
                  operation.operationId(),
                  candidate,
                  Map.of(
                      "operationType", operation.getClass().getSimpleName(),
                      "operationAuthor", operation.author()),
                  Instant.now());
          stateStore.commit(
              new WorkflowSessionStateStore.Transition(
                  WorkflowSessionStateStore.Kind.OPERATION,
                  previous,
                  next,
                  operation,
                  List.of(accepted)));
          operationLog.apply(operation);
          operationsById.put(operation.operationId(), operation);
          revision = nextRevision;
          sequence = nextSequence;
          sessionEventHub.publishCommitted(accepted);
          return operationLog.currentWorkflow();
        }

        PresenceState updatePresence(OperationActor actor, PresenceState presenceState) {
          lock.lock();
          try {
            requireOpen();
            requireJoined(actor);
            WorkflowSessionState previous = stateLocked(false);
            long nextSequence = sequence + 1L;
            WorkflowSessionState next =
                stateWith(new ArrayList<>(participants.values()), revision, nextSequence, false);
            WorkflowSessionEvent updated =
                event(
                    sessionId,
                    nextSequence,
                    revision,
                    WorkflowSessionEvent.Type.PRESENCE_UPDATED,
                    actor,
                    null,
                    null,
                    presenceState.attributes(),
                    presenceState.observedAt());
            stateStore.commit(
                new WorkflowSessionStateStore.Transition(
                    WorkflowSessionStateStore.Kind.PRESENCE,
                    previous,
                    next,
                    null,
                    List.of(updated)));
            presenceByActor.put(actor.actorId(), presenceState);
            sequence = nextSequence;
            sessionEventHub.publishCommitted(updated);
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

        WorkflowSessionState state() {
          lock.lock();
          try {
            requireOpen();
            return stateLocked(false);
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
            WorkflowSessionState previous = stateLocked(false);
            long nextSequence = sequence + 1L;
            WorkflowSessionState next =
                stateWith(new ArrayList<>(participants.values()), revision, nextSequence, true);
            WorkflowSessionEvent closedEvent =
                event(
                    sessionId,
                    nextSequence,
                    revision,
                    WorkflowSessionEvent.Type.SESSION_CLOSED,
                    owner,
                    null,
                    null,
                    Map.of(),
                    Instant.now());
            stateStore.commit(
                new WorkflowSessionStateStore.Transition(
                    WorkflowSessionStateStore.Kind.CLOSE,
                    previous,
                    next,
                    null,
                    List.of(closedEvent)));
            sequence = nextSequence;
            closed = true;
            sessionEventHub.publishCommitted(closedEvent);
          } finally {
            lock.unlock();
          }
        }

        private void verifyActorAndMode(
            CollaborationMode requestedMode, OperationActor actor, WorkflowOperation operation) {
          Objects.requireNonNull(requestedMode, "mode");
          Objects.requireNonNull(actor, "actor");
          Objects.requireNonNull(operation, "operation");
          if (mode != requestedMode) {
            throw error(
                Code.SESSION_MODE_MISMATCH,
                sessionId,
                "Requested mode '" + requestedMode + "' does not match session mode '" + mode + "'");
          }
          requireJoined(actor);
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

        private void requireJoined(OperationActor actor) {
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

        private void requireRevision(long expectedRevision) {
          if (expectedRevision != revision) {
            throw error(
                Code.REVISION_CONFLICT,
                sessionId,
                "Expected revision " + expectedRevision + " but current revision is " + revision);
          }
        }

        private WorkflowSessionState stateLocked(boolean nextClosed) {
          return stateWith(
              new ArrayList<>(participants.values()), revision, sequence, nextClosed || closed);
        }

        private WorkflowSessionState stateWith(
            List<OperationActor> stateParticipants,
            long stateRevision,
            long stateSequence,
            boolean stateClosed) {
          return new WorkflowSessionState(
              sessionId,
              mode,
              owner,
              createdAt,
              initialWorkflow,
              operationLog.currentWorkflow(),
              stateParticipants,
              operationLog.operations(),
              stateRevision,
              stateSequence,
              stateClosed);
        }

        private static boolean sameSemanticOperation(
            WorkflowOperation existing, WorkflowOperation candidate) {
          return existing.getClass().equals(candidate.getClass())
              && existing.operationId().equals(candidate.operationId())
              && existing.author().equals(candidate.author())
              && existing.affectedObjectIds().equals(candidate.affectedObjectIds())
              && existing.payload().equals(candidate.payload());
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
              operationLog.operations().size(),
              operationLog.currentWorkflow().id(),
              revision,
              sequence);
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
       * @param revision current semantic workflow revision
       * @param sequence current ordered event sequence
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

        /** Validates and defensively copies immutable session metadata. */
        public SessionSnapshot {
          requireNotBlank(sessionId, "sessionId");
          Objects.requireNonNull(mode, "mode");
          Objects.requireNonNull(owner, "owner");
          Objects.requireNonNull(createdAt, "createdAt");
          participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
          if (operationCount < 0 || revision < 0 || sequence < 0) {
            throw new IllegalArgumentException(
                "operationCount, revision and sequence must be >= 0");
          }
          requireNotBlank(workflowId, "workflowId");
        }
      }
    }
    ''',
)

# Add restoration and committed-event ingestion to the bounded event hub.
replace_once(
    "audio-core/src/main/java/org/hammer/audio/workflow/collaboration/WorkflowSessionEventHub.java",
    "  /** Opens a new event stream and publishes creation plus owner-presence events. */\n",
    '''  /** Restores an active session stream without replaying already durable historical events. */
  public void restoreSession(
      String sessionId, Workflow workflow, long sequence, long revision) {
    String requiredSessionId = requireNotBlank(sessionId, "sessionId");
    Objects.requireNonNull(workflow, "workflow");
    SessionBuffer restored =
        new SessionBuffer(
            requiredSessionId,
            workflow,
            replayCapacity,
            subscriberQueueCapacity,
            sequence,
            revision);
    SessionBuffer previous = sessions.putIfAbsent(requiredSessionId, restored);
    if (previous != null) {
      if (!previous.isClosed() || !sessions.replace(requiredSessionId, previous, restored)) {
        throw new IllegalStateException("Event stream already exists: " + requiredSessionId);
      }
      previous.stopSubscribers();
    }
  }

  /** Fans out one event that has already been committed by the durable state adapter. */
  public void publishCommitted(WorkflowSessionEvent event) {
    Objects.requireNonNull(event, "event");
    requireSession(event.sessionId()).acceptCommitted(event);
  }

  /** Opens a new event stream and publishes creation plus owner-presence events. */
''',
)

replace_once(
    "audio-core/src/main/java/org/hammer/audio/workflow/collaboration/WorkflowSessionEventHub.java",
    '''    SessionBuffer(
        String sessionId, Workflow workflow, int replayCapacity, int subscriberQueueCapacity) {
      this.sessionId = sessionId;
      this.currentWorkflow = workflow;
      this.replayCapacity = replayCapacity;
      this.subscriberQueueCapacity = subscriberQueueCapacity;
    }
''',
    '''    SessionBuffer(
        String sessionId, Workflow workflow, int replayCapacity, int subscriberQueueCapacity) {
      this(sessionId, workflow, replayCapacity, subscriberQueueCapacity, 0L, 0L);
    }

    SessionBuffer(
        String sessionId,
        Workflow workflow,
        int replayCapacity,
        int subscriberQueueCapacity,
        long sequence,
        long revision) {
      if (sequence < 0 || revision < 0) {
        throw new IllegalArgumentException("sequence and revision must be >= 0");
      }
      this.sessionId = sessionId;
      this.currentWorkflow = workflow;
      this.replayCapacity = replayCapacity;
      this.subscriberQueueCapacity = subscriberQueueCapacity;
      this.sequence = sequence;
      this.revision = revision;
    }
''',
)

replace_once(
    "audio-core/src/main/java/org/hammer/audio/workflow/collaboration/WorkflowSessionEventHub.java",
    '''      overflowed.forEach(Subscriber::stop);
      return event;
    }

    List<WorkflowSessionEvent> replay(long afterSequence) {
''',
    '''      overflowed.forEach(Subscriber::stop);
      return event;
    }

    void acceptCommitted(WorkflowSessionEvent event) {
      List<Subscriber> overflowed = new ArrayList<>();
      lock.lock();
      try {
        if (closed) {
          throw new IllegalStateException("Session event stream is closed: " + sessionId);
        }
        if (!sessionId.equals(event.sessionId())) {
          throw new IllegalArgumentException("Committed event belongs to another session");
        }
        if (event.sequence() != sequence + 1L) {
          throw new IllegalStateException(
              "Expected committed sequence " + (sequence + 1L) + " but received " + event.sequence());
        }
        long expectedRevision =
            event.type() == WorkflowSessionEvent.Type.OPERATION_ACCEPTED
                ? revision + 1L
                : revision;
        if (event.revision() != expectedRevision) {
          throw new IllegalStateException(
              "Expected committed revision " + expectedRevision + " but received " + event.revision());
        }
        sequence = event.sequence();
        revision = event.revision();
        if (event.workflow() != null) {
          currentWorkflow = event.workflow();
        }
        retainedEvents.addLast(event);
        while (retainedEvents.size() > replayCapacity) {
          retainedEvents.removeFirst();
        }
        for (Subscriber subscriber : subscribers) {
          if (!subscriber.enqueue(event)) {
            overflowed.add(subscriber);
          }
        }
        subscribers.removeAll(overflowed);
        if (event.type() == WorkflowSessionEvent.Type.SESSION_CLOSED) {
          closed = true;
        }
      } finally {
        lock.unlock();
      }
      overflowed.forEach(Subscriber::stop);
      if (event.type() == WorkflowSessionEvent.Type.SESSION_CLOSED) {
        stopSubscribers();
      }
    }

    List<WorkflowSessionEvent> replay(long afterSequence) {
''',
)

replace_once(
    "audio-core/src/main/java/org/hammer/audio/workflow/collaboration/WorkflowSessionEventHub.java",
    '''    private boolean replayGap(long afterSequence) {
      WorkflowSessionEvent oldest = retainedEvents.peekFirst();
      return oldest != null && afterSequence < oldest.sequence() - 1;
    }
''',
    '''    private boolean replayGap(long afterSequence) {
      WorkflowSessionEvent oldest = retainedEvents.peekFirst();
      return oldest == null
          ? afterSequence < sequence
          : afterSequence < oldest.sequence() - 1;
    }
''',
)

# Extend typed errors for optimistic concurrency and semantic validation.
replace_once(
    "audio-core/src/main/java/org/hammer/audio/workflow/collaboration/WorkflowSessionException.java",
    "    DUPLICATE_OPERATION_ID,\n    INVALID_OPERATION_AUTHOR\n",
    "    DUPLICATE_OPERATION_ID,\n    REVISION_CONFLICT,\n    INVALID_OPERATION_AUTHOR,\n    INVALID_WORKFLOW_OPERATION\n",
)

# Make the HTTP operation contract revision-aware and expose sequence/revision in session metadata.
replace_once(
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/WorkflowSessionApiModels.java",
    "import jakarta.validation.constraints.NotBlank;\n",
    "import jakarta.validation.constraints.Min;\nimport jakarta.validation.constraints.NotBlank;\n",
)
replace_once(
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/WorkflowSessionApiModels.java",
    '''   * @param operation semantic workflow operation payload
   */
  public record SessionOperationRequest(
      @NotNull CollaborationMode mode,
      @Valid @NotNull ActorRequest actor,
      @NotNull JsonNode operation) {
''',
    '''   * @param expectedRevision semantic revision observed by the caller
   * @param operation semantic workflow operation payload
   */
  public record SessionOperationRequest(
      @NotNull CollaborationMode mode,
      @Valid @NotNull ActorRequest actor,
      @Min(0) long expectedRevision,
      @NotNull JsonNode operation) {
''',
)
replace_once(
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/WorkflowSessionApiModels.java",
    '''   * @param workflowId canonical workflow identifier
   */
  public record SessionResponse(
      String sessionId,
      CollaborationMode mode,
      ActorResponse owner,
      Instant createdAt,
      List<ActorResponse> participants,
      int operationCount,
      String workflowId) {
''',
    '''   * @param workflowId canonical workflow identifier
   * @param revision current semantic workflow revision
   * @param sequence current ordered event sequence
   */
  public record SessionResponse(
      String sessionId,
      CollaborationMode mode,
      ActorResponse owner,
      Instant createdAt,
      List<ActorResponse> participants,
      int operationCount,
      String workflowId,
      long revision,
      long sequence) {
''',
)
replace_once(
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/WorkflowSessionApiModels.java",
    '''          snapshot.participants().stream().map(ActorResponse::from).toList(),
          snapshot.operationCount(),
          snapshot.workflowId());
''',
    '''          snapshot.participants().stream().map(ActorResponse::from).toList(),
          snapshot.operationCount(),
          snapshot.workflowId(),
          snapshot.revision(),
          snapshot.sequence());
''',
)
replace_once(
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/WorkflowSessionHttpAdapter.java",
    '''    return WorkflowProjection.fromWorkflow(
        registry.applyOperation(sessionId, request.mode(), actor, operation));
''',
    '''    return WorkflowProjection.fromWorkflow(
        registry.applyOperation(
            sessionId, request.mode(), actor, request.expectedRevision(), operation));
''',
)
replace_once(
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/WorkflowApiExceptionHandler.java",
    '''              SESSION_CLOSE_FORBIDDEN,
              DUPLICATE_OPERATION_ID ->
          HttpStatus.CONFLICT;
      case INVALID_OPERATION_AUTHOR -> HttpStatus.BAD_REQUEST;
''',
    '''              SESSION_CLOSE_FORBIDDEN,
              DUPLICATE_OPERATION_ID,
              REVISION_CONFLICT ->
          HttpStatus.CONFLICT;
      case INVALID_OPERATION_AUTHOR, INVALID_WORKFLOW_OPERATION -> HttpStatus.BAD_REQUEST;
''',
)

# Allow the application to select a durable store while preserving no-op demo behavior.
replace_once(
    "audio-app/src/main/java/org/hammer/audio/app/WorkbenchConfiguration.java",
    "import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;\n",
    "import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;\n"
    "import org.hammer.audio.workflow.collaboration.WorkflowSessionStateStore;\n",
)
replace_once(
    "audio-app/src/main/java/org/hammer/audio/app/WorkbenchConfiguration.java",
    "import org.springframework.beans.factory.annotation.Value;\n",
    "import org.springframework.beans.factory.ObjectProvider;\n"
    "import org.springframework.beans.factory.annotation.Value;\n",
)
replace_once(
    "audio-app/src/main/java/org/hammer/audio/app/WorkbenchConfiguration.java",
    '''  public WorkflowSessionRegistry workflowSessionRegistry(WorkflowSessionEventHub eventHub) {
    return new WorkflowSessionRegistry(eventHub);
  }
''',
    '''  public WorkflowSessionRegistry workflowSessionRegistry(
      WorkflowSessionEventHub eventHub,
      ObjectProvider<WorkflowSessionStateStore> stateStoreProvider) {
    WorkflowSessionStateStore stateStore =
        stateStoreProvider.getIfAvailable(WorkflowSessionStateStore::noOp);
    return new WorkflowSessionRegistry(eventHub, stateStore);
  }
''',
)

write(
    "audio-core/src/test/java/org/hammer/audio/workflow/collaboration/WorkflowSessionPersistenceBoundaryTest.java",
    r'''
    package org.hammer.audio.workflow.collaboration;

    import static org.junit.jupiter.api.Assertions.assertEquals;
    import static org.junit.jupiter.api.Assertions.assertThrows;

    import java.time.Instant;
    import java.util.ArrayList;
    import java.util.List;
    import org.hammer.audio.workflow.Workflow;
    import org.hammer.audio.workflow.WorkflowOperation;
    import org.hammer.audio.workflow.catalog.ExperimentNodeCatalog;
    import org.junit.jupiter.api.Test;

    class WorkflowSessionPersistenceBoundaryTest {

      @Test
      void restoresCommittedWorkflowAndUsesSnapshotFallbackAfterRestart() {
        RecordingStateStore store = new RecordingStateStore();
        WorkflowSessionRegistry first = new WorkflowSessionRegistry(new WorkflowSessionEventHub(), store);
        OperationActor owner = new OperationActor("actor-a", "user-a", "Alice");
        first.create(
            "session-restore",
            CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
            owner,
            workflow("workflow-restore"));
        WorkflowOperation operation =
            new WorkflowOperation.CreateNode(
                "op-create", Instant.parse("2026-07-17T12:00:00Z"), "actor-a",
                ExperimentNodeCatalog.gain("gain-1"));
        first.applyOperation(
            "session-restore",
            CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
            owner,
            0L,
            operation);

        WorkflowSessionEventHub restoredHub = new WorkflowSessionEventHub();
        WorkflowSessionRegistry restored = new WorkflowSessionRegistry(restoredHub, store);

        assertEquals(1L, restored.inspect("session-restore").revision());
        assertEquals(3L, restored.inspect("session-restore").sequence());
        assertEquals(1, restored.workflow("session-restore").nodes().size());
        List<WorkflowSessionEvent> replay = restoredHub.replay("session-restore", 0L);
        assertEquals(1, replay.size());
        assertEquals(WorkflowSessionEvent.Type.SNAPSHOT, replay.getFirst().type());
        assertEquals(3L, replay.getFirst().sequence());
      }

      @Test
      void failedDurableCommitLeavesCanonicalStateAndEventSequenceUntouched() {
        RecordingStateStore store = new RecordingStateStore();
        WorkflowSessionEventHub hub = new WorkflowSessionEventHub();
        WorkflowSessionRegistry registry = new WorkflowSessionRegistry(hub, store);
        OperationActor owner = new OperationActor("actor-a", "user-a", "Alice");
        registry.create(
            "session-failure",
            CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
            owner,
            workflow("workflow-failure"));
        store.failNextCommit = true;
        WorkflowOperation operation =
            new WorkflowOperation.CreateNode(
                "op-fail", Instant.parse("2026-07-17T12:00:00Z"), "actor-a",
                ExperimentNodeCatalog.gain("gain-fail"));

        assertThrows(
            IllegalStateException.class,
            () ->
                registry.applyOperation(
                    "session-failure",
                    CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
                    owner,
                    0L,
                    operation));
        assertEquals(0, registry.workflow("session-failure").nodes().size());
        assertEquals(0L, registry.inspect("session-failure").revision());
        assertEquals(2L, hub.currentSequence("session-failure"));
      }

      @Test
      void staleExpectedRevisionIsRejectedBeforePersistence() {
        RecordingStateStore store = new RecordingStateStore();
        WorkflowSessionRegistry registry = new WorkflowSessionRegistry(new WorkflowSessionEventHub(), store);
        OperationActor owner = new OperationActor("actor-a", "user-a", "Alice");
        registry.create(
            "session-revision",
            CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
            owner,
            workflow("workflow-revision"));
        WorkflowOperation operation =
            new WorkflowOperation.CreateNode(
                "op-stale", Instant.parse("2026-07-17T12:00:00Z"), "actor-a",
                ExperimentNodeCatalog.gain("gain-stale"));

        WorkflowSessionException exception =
            assertThrows(
                WorkflowSessionException.class,
                () ->
                    registry.applyOperation(
                        "session-revision",
                        CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
                        owner,
                        1L,
                        operation));
        assertEquals(WorkflowSessionException.Code.REVISION_CONFLICT, exception.code());
        assertEquals(1, store.transitions.size());
      }

      private static Workflow workflow(String id) {
        return new Workflow(id, "Workflow " + id, List.of(), List.of());
      }

      private static final class RecordingStateStore implements WorkflowSessionStateStore {
        private final List<Transition> transitions = new ArrayList<>();
        private WorkflowSessionState activeState;
        private boolean failNextCommit;

        @Override
        public List<WorkflowSessionState> restoreActive() {
          return activeState == null || activeState.closed() ? List.of() : List.of(activeState);
        }

        @Override
        public void commit(Transition transition) {
          if (failNextCommit) {
            failNextCommit = false;
            throw new IllegalStateException("simulated persistence failure");
          }
          transitions.add(transition);
          activeState = transition.nextState();
        }

        @Override
        public List<OutboxRecord> pendingOutbox(int limit, Instant now) {
          return List.of();
        }

        @Override
        public void markPublished(String eventId, Instant publishedAt) {}

        @Override
        public void markAttempt(String eventId, Instant nextAttemptAt, String errorMessage) {}
      }
    }
    ''',
)
