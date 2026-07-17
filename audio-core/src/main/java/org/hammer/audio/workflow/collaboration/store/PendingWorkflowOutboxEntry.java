package org.hammer.audio.workflow.collaboration.store;

import java.time.Instant;
import java.util.Objects;

/** Immutable pending collaboration event returned to an outbox dispatcher. */
public record PendingWorkflowOutboxEntry(
    String eventId,
    String sessionId,
    long sequence,
    long revision,
    String eventType,
    Instant occurredAt,
    String payload,
    int attemptCount,
    Instant nextAttemptAt) {

  public PendingWorkflowOutboxEntry {
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
