package org.hammer.audio.workflow.collaboration;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;

/**
 * Bounded, transport-neutral replay and fan-out hub for collaboration-session events.
 *
 * <p>Each session owns an independent sequence and semantic revision. Subscribers are isolated by a
 * bounded queue and a virtual dispatch thread, so a failed or slow transport cannot block accepted
 * workflow operations. A subscriber that throws or exhausts its queue is removed automatically.
 */
public final class WorkflowSessionEventHub {

  private static final String ACTOR_FIELD = "actor";

  static final int DEFAULT_REPLAY_CAPACITY = 256;
  static final int DEFAULT_SUBSCRIBER_QUEUE_CAPACITY = 64;

  private final int replayCapacity;
  private final int subscriberQueueCapacity;
  private final Map<String, SessionBuffer> sessions = new ConcurrentHashMap<>();

  /** Creates a hub with production defaults for replay and subscriber buffering. */
  public WorkflowSessionEventHub() {
    this(DEFAULT_REPLAY_CAPACITY, DEFAULT_SUBSCRIBER_QUEUE_CAPACITY);
  }

  /** Creates a hub with explicit bounded capacities, primarily for tests and tuning. */
  public WorkflowSessionEventHub(int replayCapacity, int subscriberQueueCapacity) {
    if (replayCapacity <= 0) {
      throw new IllegalArgumentException("replayCapacity must be > 0");
    }
    if (subscriberQueueCapacity <= 0) {
      throw new IllegalArgumentException("subscriberQueueCapacity must be > 0");
    }
    this.replayCapacity = replayCapacity;
    this.subscriberQueueCapacity = subscriberQueueCapacity;
  }

  /** Opens a new event stream and publishes creation plus owner-presence events. */
  public void openSession(String sessionId, OperationActor owner, Workflow workflow) {
    String requiredSessionId = requireNotBlank(sessionId, "sessionId");
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(workflow, "workflow");
    SessionBuffer created =
        new SessionBuffer(requiredSessionId, workflow, replayCapacity, subscriberQueueCapacity);
    installSession(requiredSessionId, created);
    created.publish(
        WorkflowSessionEvent.Type.SESSION_CREATED,
        owner,
        null,
        workflow,
        Map.of(),
        false,
        Instant.now());
    created.publish(
        WorkflowSessionEvent.Type.PRESENCE_JOINED,
        owner,
        null,
        null,
        Map.of(),
        false,
        Instant.now());
  }

  /**
   * Restores an event stream at a durable sequence/revision without publishing historical events.
   *
   * <p>The first reconnect cursor at or before the recovery boundary receives a canonical snapshot
   * rather than fabricated creation, presence or operation events.
   */
  public void restoreSession(String sessionId, Workflow workflow, long sequence, long revision) {
    String requiredSessionId = requireNotBlank(sessionId, "sessionId");
    Objects.requireNonNull(workflow, "workflow");
    if (sequence < 0) {
      throw new IllegalArgumentException("sequence must be >= 0");
    }
    if (revision < 0 || revision > sequence) {
      throw new IllegalArgumentException("revision must be between 0 and sequence");
    }
    SessionBuffer restored =
        SessionBuffer.restored(
            requiredSessionId,
            workflow,
            replayCapacity,
            subscriberQueueCapacity,
            sequence,
            revision);
    installSession(requiredSessionId, restored);
  }

  /** Publishes a newly joined actor without changing the semantic revision. */
  public void actorJoined(String sessionId, OperationActor actor) {
    requireSession(sessionId)
        .publish(
            WorkflowSessionEvent.Type.PRESENCE_JOINED,
            Objects.requireNonNull(actor, ACTOR_FIELD),
            null,
            null,
            Map.of(),
            false,
            Instant.now());
  }

  /** Publishes non-semantic presence data without changing the semantic revision. */
  public void presenceUpdated(String sessionId, OperationActor actor, PresenceState presenceState) {
    Objects.requireNonNull(actor, ACTOR_FIELD);
    Objects.requireNonNull(presenceState, "presenceState");
    requireSession(sessionId)
        .publish(
            WorkflowSessionEvent.Type.PRESENCE_UPDATED,
            actor,
            null,
            null,
            presenceState.attributes(),
            false,
            presenceState.observedAt());
  }

  /** Publishes an actor departure without deleting the session stream. */
  public void actorLeft(String sessionId, OperationActor actor) {
    requireSession(sessionId)
        .publish(
            WorkflowSessionEvent.Type.PRESENCE_LEFT,
            Objects.requireNonNull(actor, ACTOR_FIELD),
            null,
            null,
            Map.of(),
            false,
            Instant.now());
  }

  /** Publishes one server-accepted semantic operation and advances the semantic revision. */
  public WorkflowSessionEvent operationAccepted(
      String sessionId,
      OperationActor actor,
      WorkflowOperation operation,
      Workflow resultingWorkflow) {
    Objects.requireNonNull(operation, "operation");
    Map<String, String> attributes =
        Map.of(
            "operationType", operation.getClass().getSimpleName(),
            "operationAuthor", operation.author());
    return requireSession(sessionId)
        .publish(
            WorkflowSessionEvent.Type.OPERATION_ACCEPTED,
            Objects.requireNonNull(actor, ACTOR_FIELD),
            operation.operationId(),
            Objects.requireNonNull(resultingWorkflow, "resultingWorkflow"),
            attributes,
            true,
            Instant.now());
  }

  /** Publishes closure and terminates all active transport subscriptions. */
  public void closeSession(String sessionId, OperationActor requestedBy) {
    SessionBuffer buffer = requireSession(sessionId);
    buffer.publish(
        WorkflowSessionEvent.Type.SESSION_CLOSED,
        Objects.requireNonNull(requestedBy, "requestedBy"),
        null,
        null,
        Map.of(),
        false,
        Instant.now());
    buffer.markClosed();
  }

  /** Returns retained events after the supplied sequence, or a canonical snapshot after a gap. */
  public List<WorkflowSessionEvent> replay(String sessionId, long afterSequence) {
    if (afterSequence < 0) {
      throw new IllegalArgumentException("afterSequence must be >= 0");
    }
    return requireSession(sessionId).replay(afterSequence);
  }

  /**
   * Subscribes to retained and future events after a sequence.
   *
   * <p>The callback executes on a dedicated virtual thread. Closing the returned handle is
   * idempotent.
   */
  public Subscription subscribe(
      String sessionId, long afterSequence, Consumer<WorkflowSessionEvent> callback) {
    if (afterSequence < 0) {
      throw new IllegalArgumentException("afterSequence must be >= 0");
    }
    return requireSession(sessionId)
        .subscribe(afterSequence, Objects.requireNonNull(callback, "callback"));
  }

  /** Returns the latest event sequence for diagnostics and reconnect cursors. */
  public long currentSequence(String sessionId) {
    return requireSession(sessionId).currentSequence();
  }

  /** Returns the latest semantic revision for diagnostics and optimistic concurrency. */
  public long currentRevision(String sessionId) {
    return requireSession(sessionId).currentRevision();
  }

  /** Returns the number of currently active subscribers. */
  public int subscriberCount(String sessionId) {
    return requireSession(sessionId).subscriberCount();
  }

  private void installSession(String sessionId, SessionBuffer created) {
    SessionBuffer previous = sessions.putIfAbsent(sessionId, created);
    if (previous != null) {
      if (!previous.isClosed() || !sessions.replace(sessionId, previous, created)) {
        throw new IllegalStateException("Event stream already exists: " + sessionId);
      }
      previous.stopSubscribers();
    }
  }

  private SessionBuffer requireSession(String sessionId) {
    String requiredSessionId = requireNotBlank(sessionId, "sessionId");
    SessionBuffer buffer = sessions.get(requiredSessionId);
    if (buffer == null) {
      throw new IllegalArgumentException("Unknown session event stream: " + requiredSessionId);
    }
    return buffer;
  }

  private static String requireNotBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  /** Handle for one active event subscription. */
  @FunctionalInterface
  public interface Subscription extends AutoCloseable {
    @Override
    void close();
  }

  private static final class SessionBuffer {
    private static final long NO_RECOVERY_BOUNDARY = -1;

    private final String sessionId;
    private final int replayCapacity;
    private final int subscriberQueueCapacity;
    private final long recoveryBoundary;
    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<WorkflowSessionEvent> retainedEvents = new ArrayDeque<>();
    private final Set<Subscriber> subscribers = new LinkedHashSet<>();
    private long sequence;
    private long revision;
    private Workflow currentWorkflow;
    private boolean closed;

    SessionBuffer(
        String sessionId, Workflow workflow, int replayCapacity, int subscriberQueueCapacity) {
      this(
          sessionId, workflow, replayCapacity, subscriberQueueCapacity, 0, 0, NO_RECOVERY_BOUNDARY);
    }

    private SessionBuffer(
        String sessionId,
        Workflow workflow,
        int replayCapacity,
        int subscriberQueueCapacity,
        long sequence,
        long revision,
        long recoveryBoundary) {
      this.sessionId = sessionId;
      this.currentWorkflow = workflow;
      this.replayCapacity = replayCapacity;
      this.subscriberQueueCapacity = subscriberQueueCapacity;
      this.sequence = sequence;
      this.revision = revision;
      this.recoveryBoundary = recoveryBoundary;
    }

    static SessionBuffer restored(
        String sessionId,
        Workflow workflow,
        int replayCapacity,
        int subscriberQueueCapacity,
        long sequence,
        long revision) {
      return new SessionBuffer(
          sessionId,
          workflow,
          replayCapacity,
          subscriberQueueCapacity,
          sequence,
          revision,
          sequence);
    }

    WorkflowSessionEvent publish(
        WorkflowSessionEvent.Type type,
        OperationActor actor,
        String operationId,
        Workflow workflow,
        Map<String, String> attributes,
        boolean semantic,
        Instant occurredAt) {
      List<Subscriber> overflowed = new ArrayList<>();
      WorkflowSessionEvent event;
      lock.lock();
      try {
        if (closed) {
          throw new IllegalStateException("Session event stream is closed: " + sessionId);
        }
        sequence++;
        if (semantic) {
          revision++;
        }
        if (workflow != null) {
          currentWorkflow = workflow;
        }
        event =
            new WorkflowSessionEvent(
                eventId(sequence),
                sessionId,
                sequence,
                revision,
                occurredAt,
                type,
                actor,
                operationId,
                workflow,
                attributes);
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
      } finally {
        lock.unlock();
      }
      overflowed.forEach(Subscriber::stop);
      return event;
    }

    List<WorkflowSessionEvent> replay(long afterSequence) {
      lock.lock();
      try {
        return replayLocked(afterSequence);
      } finally {
        lock.unlock();
      }
    }

    Subscription subscribe(long afterSequence, Consumer<WorkflowSessionEvent> callback) {
      Subscriber subscriber;
      lock.lock();
      try {
        if (closed) {
          throw new IllegalStateException("Session event stream is closed: " + sessionId);
        }
        List<WorkflowSessionEvent> initialEvents = replayLocked(afterSequence);
        int queueCapacity = Math.max(subscriberQueueCapacity, initialEvents.size() + 1);
        Subscriber createdSubscriber = new Subscriber(sessionId, queueCapacity, callback);
        createdSubscriber.setRemovalCallback(() -> removeSubscriber(createdSubscriber));
        subscriber = createdSubscriber;
        if (!subscriber.enqueueAll(initialEvents)) {
          throw new IllegalStateException("Unable to queue retained session events");
        }
        subscribers.add(subscriber);
        subscriber.start();
      } finally {
        lock.unlock();
      }
      return subscriber::stop;
    }

    private void removeSubscriber(Subscriber subscriber) {
      lock.lock();
      try {
        subscribers.remove(subscriber);
      } finally {
        lock.unlock();
      }
    }

    private List<WorkflowSessionEvent> replayLocked(long afterSequence) {
      if (recoveryBoundary >= 0 && afterSequence <= recoveryBoundary) {
        return List.of(snapshotEvent());
      }
      if (afterSequence > sequence || replayGap(afterSequence)) {
        return List.of(snapshotEvent());
      }
      return retainedEvents.stream().filter(event -> event.sequence() > afterSequence).toList();
    }

    private boolean replayGap(long afterSequence) {
      WorkflowSessionEvent oldest = retainedEvents.peekFirst();
      return oldest != null && afterSequence < oldest.sequence() - 1;
    }

    private WorkflowSessionEvent snapshotEvent() {
      return new WorkflowSessionEvent(
          eventId(sequence) + ":snapshot",
          sessionId,
          sequence,
          revision,
          Instant.now(),
          WorkflowSessionEvent.Type.SNAPSHOT,
          null,
          null,
          currentWorkflow,
          Map.of());
    }

    long currentSequence() {
      lock.lock();
      try {
        return sequence;
      } finally {
        lock.unlock();
      }
    }

    long currentRevision() {
      lock.lock();
      try {
        return revision;
      } finally {
        lock.unlock();
      }
    }

    int subscriberCount() {
      lock.lock();
      try {
        return subscribers.size();
      } finally {
        lock.unlock();
      }
    }

    boolean isClosed() {
      lock.lock();
      try {
        return closed;
      } finally {
        lock.unlock();
      }
    }

    void markClosed() {
      lock.lock();
      try {
        closed = true;
      } finally {
        lock.unlock();
      }
    }

    void stopSubscribers() {
      List<Subscriber> active;
      lock.lock();
      try {
        active = List.copyOf(subscribers);
        subscribers.clear();
        closed = true;
      } finally {
        lock.unlock();
      }
      active.forEach(Subscriber::stop);
    }

    private String eventId(long eventSequence) {
      return sessionId + ":" + eventSequence;
    }
  }

  private static final class Subscriber {
    private final BlockingQueue<WorkflowSessionEvent> queue;
    private final Consumer<WorkflowSessionEvent> callback;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final String threadName;
    private volatile Thread dispatchThread;
    private Runnable removalCallback;

    Subscriber(String sessionId, int queueCapacity, Consumer<WorkflowSessionEvent> callback) {
      this.queue = new ArrayBlockingQueue<>(queueCapacity);
      this.callback = callback;
      this.threadName = "workflow-session-events-" + sessionId;
    }

    void setRemovalCallback(Runnable removalCallback) {
      this.removalCallback = Objects.requireNonNull(removalCallback, "removalCallback");
    }

    boolean enqueue(WorkflowSessionEvent event) {
      return !stopped.get() && queue.offer(event);
    }

    boolean enqueueAll(List<WorkflowSessionEvent> events) {
      for (WorkflowSessionEvent event : events) {
        if (!enqueue(event)) {
          return false;
        }
      }
      return true;
    }

    void start() {
      dispatchThread = Thread.ofVirtual().name(threadName).start(this::dispatch);
    }

    void stop() {
      if (stopped.compareAndSet(false, true)) {
        Thread thread = dispatchThread;
        if (thread != null) {
          thread.interrupt();
        }
      }
    }

    private void dispatch() {
      try {
        while (!stopped.get()) {
          WorkflowSessionEvent event = queue.take();
          callback.accept(event);
          if (event.type() == WorkflowSessionEvent.Type.SESSION_CLOSED) {
            break;
          }
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      } catch (RuntimeException ignored) {
        // A failed transport callback is isolated and removed below.
      } finally {
        stopped.set(true);
        removalCallback.run();
      }
    }
  }
}
