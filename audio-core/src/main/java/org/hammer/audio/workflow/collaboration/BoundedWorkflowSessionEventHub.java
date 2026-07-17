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
    return buffers
        .computeIfAbsent(requireSessionId(sessionId), ignored -> new SessionBuffer())
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
