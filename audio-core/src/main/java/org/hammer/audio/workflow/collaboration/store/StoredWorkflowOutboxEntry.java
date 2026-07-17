package org.hammer.audio.workflow.collaboration.store;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable durable collaboration outbox entry.
 *
 * @param eventId stable idempotency identifier for the event
 * @param sessionId owning collaboration-session identifier
 * @param sequence durable session event sequence
 * @param revision semantic revision associated with the event
 * @param eventType stable collaboration event type
 * @param occurredAt event occurrence timestamp
 * @param payload deterministic serialized event payload
 * @param attemptCount number of completed publication attempts
 * @param nextAttemptAt earliest timestamp for the next publication attempt
 * @param publishedAt successful publication timestamp, or {@code null} while pending
 */
public record StoredWorkflowOutboxEntry(
    String eventId,
    String sessionId,
    long sequence,
    long revision,
    String eventType,
    Instant occurredAt,
    String payload,
    int attemptCount,
    Instant nextAttemptAt,
    Instant publishedAt) {

  public StoredWorkflowOutboxEntry {
    eventId = requireNotBlank(eventId, "eventId");
    sessionId = requireNotBlank(sessionId, "sessionId");
    requirePositive(sequence, "sequence");
    requirePositive(revision, "revision");
    eventType = requireNotBlank(eventType, "eventType");
    Objects.requireNonNull(occurredAt, "occurredAt");
    payload = requireNotBlank(payload, "payload");
    if (attemptCount < 0) {
      throw new IllegalArgumentException("attemptCount must be >= 0");
    }
    Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
  }

  /** Returns whether the event still awaits successful publication. */
  public boolean pending() {
    return publishedAt == null;
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static void requirePositive(long value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be > 0");
    }
  }
}
