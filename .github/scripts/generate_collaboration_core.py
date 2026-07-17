from pathlib import Path
from textwrap import dedent

ROOT = Path(__file__).resolve().parents[2]


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(dedent(content).lstrip(), encoding="utf-8")


write(
    "audio-core/src/main/java/org/hammer/audio/workflow/collaboration/WorkflowSessionException.java",
    r'''
    package org.hammer.audio.workflow.collaboration;

    import java.util.Objects;

    /** Typed collaboration/session failure suitable for transport-independent error mapping. */
    public final class WorkflowSessionException extends RuntimeException {

      /** Stable machine-readable failure codes. */
      public enum Code {
        SESSION_NOT_FOUND,
        SESSION_ALREADY_EXISTS,
        PRIVATE_WORKSPACE_ACCESS_DENIED,
        ACTOR_METADATA_MISMATCH,
        ACTOR_NOT_JOINED,
        SESSION_MODE_MISMATCH,
        SESSION_CLOSE_FORBIDDEN,
        INVALID_OPERATION_AUTHOR,
        REVISION_CONFLICT,
        NOTHING_TO_UNDO,
        NOTHING_TO_REDO,
        INVALID_WORKFLOW_OPERATION
      }

      private final Code code;
      private final String sessionId;

      public WorkflowSessionException(Code code, String sessionId, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
        this.sessionId = sessionId;
      }

      public Code code() {
        return code;
      }

      public String sessionId() {
        return sessionId;
      }
    }
    ''',
)

write(
    "audio-core/src/main/java/org/hammer/audio/workflow/collaboration/WorkflowPresence.java",
    r'''
    package org.hammer.audio.workflow.collaboration;

    import java.time.Instant;
    import java.util.List;
    import java.util.Objects;

    /** Non-semantic, disposable presence information for one actor. */
    public record WorkflowPresence(
        String actorId,
        double cursorX,
        double cursorY,
        List<String> selectedObjectIds,
        double viewportX,
        double viewportY,
        double viewportZoom,
        Instant updatedAt) {

      public WorkflowPresence {
        Objects.requireNonNull(actorId, "actorId");
        if (actorId.isBlank()) {
          throw new IllegalArgumentException("actorId must not be blank");
        }
        selectedObjectIds = List.copyOf(Objects.requireNonNull(selectedObjectIds, "selectedObjectIds"));
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (!Double.isFinite(cursorX)
            || !Double.isFinite(cursorY)
            || !Double.isFinite(viewportX)
            || !Double.isFinite(viewportY)
            || !Double.isFinite(viewportZoom)
            || viewportZoom <= 0.0) {
          throw new IllegalArgumentException("presence coordinates and zoom must be finite; zoom > 0");
        }
      }
    }
    ''',
)

write(
    "audio-core/src/main/java/org/hammer/audio/workflow/collaboration/WorkflowUndoEntry.java",
    r'''
    package org.hammer.audio.workflow.collaboration;

    import java.time.Instant;
    import java.util.Objects;

    /** Audit marker connecting a semantic undo with its target and optional redo. */
    public record WorkflowUndoEntry(
        String requestedByActor,
        UndoScope scope,
        String targetOperationId,
        String inverseOperationId,
        String redoOperationId,
        boolean redone,
        Instant createdAt) {

      public WorkflowUndoEntry {
        requireNotBlank(requestedByActor, "requestedByActor");
        Objects.requireNonNull(scope, "scope");
        requireNotBlank(targetOperationId, "targetOperationId");
        requireNotBlank(inverseOperationId, "inverseOperationId");
        Objects.requireNonNull(createdAt, "createdAt");
      }

      public WorkflowUndoEntry markRedone(String operationId) {
        return new WorkflowUndoEntry(
            requestedByActor,
            scope,
            targetOperationId,
            inverseOperationId,
            requireNotBlank(operationId, "operationId"),
            true,
            createdAt);
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
    "audio-core/src/main/java/org/hammer/audio/workflow/collaboration/WorkflowSessionState.java",
    r'''
    package org.hammer.audio.workflow.collaboration;

    import java.time.Instant;
    import java.util.LinkedHashMap;
    import java.util.List;
    import java.util.Map;
    import java.util.Objects;
    import org.hammer.audio.workflow.Workflow;
    import org.hammer.audio.workflow.WorkflowOperation;

    /** Complete recoverable state of one collaboration session. */
    public record WorkflowSessionState(
        String sessionId,
        CollaborationMode mode,
        OperationActor owner,
        Instant createdAt,
        Workflow initialWorkflow,
        Workflow workflow,
        List<OperationActor> participants,
        List<WorkflowOperation> operations,
        Map<String, WorkflowPresence> presence,
        List<WorkflowUndoEntry> undoEntries,
        long revision,
        long sequence) {

      public WorkflowSessionState {
        requireNotBlank(sessionId, "sessionId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(initialWorkflow, "initialWorkflow");
        Objects.requireNonNull(workflow, "workflow");
        participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
        operations = List.copyOf(Objects.requireNonNull(operations, "operations"));
        presence = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(presence, "presence")));
        undoEntries = List.copyOf(Objects.requireNonNull(undoEntries, "undoEntries"));
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
    "audio-core/src/main/java/org/hammer/audio/workflow/collaboration/WorkflowSessionEvent.java",
    r'''
    package org.hammer.audio.workflow.collaboration;

    import java.time.Instant;
    import java.util.Map;
    import java.util.Objects;
    import java.util.UUID;

    /** Ordered server fact delivered to collaboration transports. */
    public record WorkflowSessionEvent(
        String eventId,
        String sessionId,
        long sequence,
        long revision,
        Type type,
        Instant occurredAt,
        OperationActor actor,
        String operationId,
        WorkflowSessionState state,
        Map<String, String> details) {

      /** Event categories kept independent from SSE, WebSocket and broker protocols. */
      public enum Type {
        SESSION_CREATED,
        ACTOR_JOINED,
        ACTOR_LEFT,
        SESSION_CLOSED,
        OPERATION_ACCEPTED,
        PRESENCE_UPDATED,
        PRESENCE_CLEARED,
        UNDO_ACCEPTED,
        REDO_ACCEPTED,
        SNAPSHOT
      }

      public WorkflowSessionEvent {
        requireNotBlank(eventId, "eventId");
        requireNotBlank(sessionId, "sessionId");
        if (sequence < 0 || revision < 0) {
          throw new IllegalArgumentException("sequence and revision must be >= 0");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        details = Map.copyOf(Objects.requireNonNull(details, "details"));
      }

      public static WorkflowSessionEvent create(
          String sessionId,
          long sequence,
          long revision,
          Type type,
          OperationActor actor,
          String operationId,
          WorkflowSessionState state,
          Map<String, String> details) {
        return new WorkflowSessionEvent(
            UUID.randomUUID().toString(),
            sessionId,
            sequence,
            revision,
            type,
            Instant.now(),
            actor,
            operationId,
            state,
            details);
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
    "audio-core/src/main/java/org/hammer/audio/workflow/collaboration/WorkflowSessionEventSink.java",
    r'''
    package org.hammer.audio.workflow.collaboration;

    /** Transport-independent destination for committed session events. */
    @FunctionalInterface
    public interface WorkflowSessionEventSink {
      void publish(WorkflowSessionEvent event);

      static WorkflowSessionEventSink noOp() {
        return ignored -> {};
      }
    }
    ''',
)

write(
    "audio-core/src/main/java/org/hammer/audio/workflow/collaboration/WorkflowSessionStateStore.java",
    r'''
    package org.hammer.audio.workflow.collaboration;

    import java.util.List;
    import java.util.Objects;
    import org.hammer.audio.workflow.WorkflowOperation;

    /** Persistence/outbox boundary used by the session aggregate. */
    public interface WorkflowSessionStateStore {

      /** Restores all active sessions at application startup. */
      List<WorkflowSessionState> restore();

      /** Atomically persists one state transition and its outbound event. */
      void commit(Transition transition);

      /** Kinds are deliberately storage-neutral and suitable for audit projections. */
      enum Kind {
        CREATE,
        MEMBERSHIP,
        OPERATION,
        PRESENCE,
        UNDO,
        REDO,
        CLOSE
      }

      /** One optimistic state transition. A null previous state is valid only for CREATE. */
      record Transition(
          Kind kind,
          WorkflowSessionState previousState,
          WorkflowSessionState nextState,
          WorkflowOperation operation,
          WorkflowSessionEvent event) {

        public Transition {
          Objects.requireNonNull(kind, "kind");
          Objects.requireNonNull(nextState, "nextState");
          Objects.requireNonNull(event, "event");
          if (kind == Kind.CREATE && previousState != null) {
            throw new IllegalArgumentException("CREATE must not have a previous state");
          }
          if (kind != Kind.CREATE && previousState == null) {
            throw new IllegalArgumentException(kind + " requires a previous state");
          }
        }
      }

      static WorkflowSessionStateStore noOp() {
        return new WorkflowSessionStateStore() {
          @Override
          public List<WorkflowSessionState> restore() {
            return List.of();
          }

          @Override
          public void commit(Transition transition) {
            Objects.requireNonNull(transition, "transition");
          }
        };
      }
    }
    ''',
)

write(
    "audio-core/src/main/java/org/hammer/audio/workflow/collaboration/InMemoryWorkflowSessionStateStore.java",
    r'''
    package org.hammer.audio.workflow.collaboration;

    import java.util.Comparator;
    import java.util.List;
    import java.util.Map;
    import java.util.Objects;
    import java.util.concurrent.ConcurrentHashMap;

    /** In-memory state store used by the demo profile and focused tests. */
    public final class InMemoryWorkflowSessionStateStore implements WorkflowSessionStateStore {

      private final Map<String, WorkflowSessionState> states = new ConcurrentHashMap<>();
      private final WorkflowSessionEventSink eventSink;

      public InMemoryWorkflowSessionStateStore(WorkflowSessionEventSink eventSink) {
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
      }

      @Override
      public List<WorkflowSessionState> restore() {
        return states.values().stream()
            .sorted(Comparator.comparing(WorkflowSessionState::sessionId))
            .toList();
      }

      @Override
      public synchronized void commit(Transition transition) {
        Objects.requireNonNull(transition, "transition");
        String sessionId = transition.nextState().sessionId();
        WorkflowSessionState current = states.get(sessionId);
        if (transition.kind() == Kind.CREATE) {
          if (current != null) {
            throw new WorkflowSessionException(
                WorkflowSessionException.Code.SESSION_ALREADY_EXISTS,
                sessionId,
                "Session already exists: " + sessionId);
          }
        } else {
          WorkflowSessionState expected = transition.previousState();
          if (current == null
              || current.revision() != expected.revision()
              || current.sequence() != expected.sequence()) {
            throw new WorkflowSessionException(
                WorkflowSessionException.Code.REVISION_CONFLICT,
                sessionId,
                "Session revision/sequence changed while applying transition");
          }
        }
        if (transition.kind() == Kind.CLOSE) {
          states.remove(sessionId);
        } else {
          states.put(sessionId, transition.nextState());
        }
        eventSink.publish(transition.event());
      }
    }
    ''',
)

write(
    "audio-core/src/main/java/org/hammer/audio/workflow/collaboration/BoundedWorkflowSessionEventHub.java",
    r'''
    package org.hammer.audio.workflow.collaboration;

    import java.util.ArrayDeque;
    import java.util.ArrayList;
    import java.util.HashSet;
    import java.util.List;
    import java.util.Map;
    import java.util.Objects;
    import java.util.Set;
    import java.util.concurrent.ConcurrentHashMap;
    import java.util.concurrent.atomic.AtomicLong;
    import java.util.function.Consumer;

    /** Bounded replay hub with idempotent publication and transport-neutral subscriptions. */
    public final class BoundedWorkflowSessionEventHub implements WorkflowSessionEventSink {

      private final int capacity;
      private final Map<String, SessionBuffer> buffers = new ConcurrentHashMap<>();
      private final AtomicLong subscriberIds = new AtomicLong();

      public BoundedWorkflowSessionEventHub(int capacity) {
        if (capacity < 1) {
          throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
      }

      @Override
      public void publish(WorkflowSessionEvent event) {
        Objects.requireNonNull(event, "event");
        buffers.computeIfAbsent(event.sessionId(), ignored -> new SessionBuffer()).publish(event);
      }

      public Replay replay(String sessionId, long afterSequence) {
        return buffers.computeIfAbsent(requireSessionId(sessionId), ignored -> new SessionBuffer())
            .replay(afterSequence);
      }

      public Subscription subscribe(String sessionId, Consumer<WorkflowSessionEvent> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        SessionBuffer buffer =
            buffers.computeIfAbsent(requireSessionId(sessionId), ignored -> new SessionBuffer());
        long id = subscriberIds.incrementAndGet();
        buffer.addSubscriber(id, consumer);
        return () -> buffer.removeSubscriber(id);
      }

      private static String requireSessionId(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (sessionId.isBlank()) {
          throw new IllegalArgumentException("sessionId must not be blank");
        }
        return sessionId;
      }

      /** Replay result. A gap means the caller must first send a canonical snapshot. */
      public record Replay(boolean gap, long latestSequence, List<WorkflowSessionEvent> events) {
        public Replay {
          events = List.copyOf(events);
        }
      }

      @FunctionalInterface
      public interface Subscription extends AutoCloseable {
        @Override
        void close();
      }

      private final class SessionBuffer {
        private final ArrayDeque<WorkflowSessionEvent> events = new ArrayDeque<>();
        private final Set<String> eventIds = new HashSet<>();
        private final Map<Long, Consumer<WorkflowSessionEvent>> subscribers = new ConcurrentHashMap<>();

        void publish(WorkflowSessionEvent event) {
          List<Map.Entry<Long, Consumer<WorkflowSessionEvent>>> targets;
          synchronized (this) {
            if (!eventIds.add(event.eventId())) {
              return;
            }
            events.addLast(event);
            while (events.size() > capacity) {
              WorkflowSessionEvent removed = events.removeFirst();
              eventIds.remove(removed.eventId());
            }
            targets = new ArrayList<>(subscribers.entrySet());
          }
          for (Map.Entry<Long, Consumer<WorkflowSessionEvent>> target : targets) {
            try {
              target.getValue().accept(event);
            } catch (RuntimeException ex) {
              subscribers.remove(target.getKey());
            }
          }
        }

        synchronized Replay replay(long afterSequence) {
          long latest = events.isEmpty() ? 0L : events.getLast().sequence();
          long oldest = events.isEmpty() ? latest : events.getFirst().sequence();
          boolean gap = afterSequence > 0 && !events.isEmpty() && afterSequence + 1 < oldest;
          List<WorkflowSessionEvent> replay =
              events.stream().filter(event -> event.sequence() > afterSequence).toList();
          return new Replay(gap, latest, replay);
        }

        void addSubscriber(long id, Consumer<WorkflowSessionEvent> consumer) {
          subscribers.put(id, consumer);
        }

        void removeSubscriber(long id) {
          subscribers.remove(id);
        }
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
          SessionEntry previous = sessions.putIfAbsent(restored.sessionId(), SessionEntry.restore(restored));
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
        SessionEntry created = SessionEntry.create(requiredSessionId, mode, owner, initialWorkflow);
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
          String sessionId,
          CollaborationMode mode,
          OperationActor actor,
          WorkflowOperation operation) {
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
          String sessionId,
          OperationActor actor,
          long expectedRevision,
          String targetOperationId) {
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
            String sessionId,
            CollaborationMode mode,
            OperationActor owner,
            Workflow initialWorkflow) {
          return WorkflowSessionRegistry.this.new SessionEntry(
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
              WorkflowSessionRegistry.this.new SessionEntry(
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

        synchronized MutationResult updatePresence(
            OperationActor actor, WorkflowPresence newPresence) {
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
    ''',
)

write(
    "audio-core/src/test/java/org/hammer/audio/workflow/collaboration/WorkflowSessionRevisionAndEventTest.java",
    r'''
    package org.hammer.audio.workflow.collaboration;

    import static org.junit.jupiter.api.Assertions.assertEquals;
    import static org.junit.jupiter.api.Assertions.assertThrows;
    import static org.junit.jupiter.api.Assertions.assertTrue;

    import java.time.Instant;
    import java.util.ArrayList;
    import java.util.List;
    import org.hammer.audio.workflow.Metadata;
    import org.hammer.audio.workflow.Node;
    import org.hammer.audio.workflow.Workflow;
    import org.hammer.audio.workflow.WorkflowOperation;
    import org.junit.jupiter.api.Test;

    class WorkflowSessionRevisionAndEventTest {

      private static final OperationActor OWNER =
          new OperationActor("actor.owner", "user.owner", "Owner");
      private static final OperationActor GUEST =
          new OperationActor("actor.guest", "user.guest", "Guest");

      @Test
      void acceptedOperationsAreRevisionCheckedAndPublishedInOrder() {
        BoundedWorkflowSessionEventHub hub = new BoundedWorkflowSessionEventHub(16);
        WorkflowSessionRegistry registry =
            new WorkflowSessionRegistry(new InMemoryWorkflowSessionStateStore(hub));
        registry.create(
            "session", CollaborationMode.SHARED_SESSION_PERSONAL_UNDO, OWNER, emptyWorkflow());
        registry.join("session", GUEST);

        List<WorkflowSessionEvent> delivered = new ArrayList<>();
        try (BoundedWorkflowSessionEventHub.Subscription ignored =
            hub.subscribe("session", delivered::add)) {
          WorkflowOperation operation = createNode("op-1", OWNER.actorId(), "node-1");
          WorkflowSessionRegistry.MutationResult result =
              registry.applyOperation(
                  "session",
                  CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
                  OWNER,
                  0L,
                  operation);
          assertEquals(1L, result.session().revision());
          assertEquals(1, result.workflow().nodes().size());
        }

        assertEquals(1, delivered.size());
        assertEquals(WorkflowSessionEvent.Type.OPERATION_ACCEPTED, delivered.getFirst().type());
        assertThrows(
            WorkflowSessionException.class,
            () ->
                registry.applyOperation(
                    "session",
                    CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
                    OWNER,
                    0L,
                    createNode("op-stale", OWNER.actorId(), "node-2")));
      }

      @Test
      void personalUndoAndRedoAreSemanticOperations() {
        BoundedWorkflowSessionEventHub hub = new BoundedWorkflowSessionEventHub(16);
        WorkflowSessionRegistry registry =
            new WorkflowSessionRegistry(new InMemoryWorkflowSessionStateStore(hub));
        registry.create(
            "session", CollaborationMode.SHARED_SESSION_PERSONAL_UNDO, OWNER, emptyWorkflow());
        registry.applyOperation(
            "session",
            CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
            OWNER,
            0L,
            createNode("op-1", OWNER.actorId(), "node-1"));

        WorkflowSessionRegistry.MutationResult undone = registry.undo("session", OWNER, 1L, null);
        assertTrue(undone.workflow().nodes().isEmpty());
        assertEquals(2L, undone.session().revision());

        WorkflowSessionRegistry.MutationResult redone = registry.redo("session", OWNER, 2L);
        assertEquals(1, redone.workflow().nodes().size());
        assertEquals(3L, redone.session().revision());
        assertEquals(3, registry.operations("session").size());
      }

      private static WorkflowOperation createNode(String operationId, String author, String nodeId) {
        Node node = new Node(nodeId, "input", "Input", List.of(), List.of(), Metadata.empty());
        return new WorkflowOperation.CreateNode(operationId, Instant.now(), author, node);
      }

      private static Workflow emptyWorkflow() {
        return new Workflow("workflow.session", "Session workflow", List.of(), List.of());
      }
    }
    ''',
)

print("Generated collaboration core")
