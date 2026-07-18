package org.hammer.audio.workflow.collaboration.outbox;

import java.time.Instant;
import java.util.Objects;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowOutboxEntry;

/**
 * Transport-neutral envelope published from one committed durable outbox row.
 *
 * @param eventId stable transport idempotency key
 * @param sessionId owning collaboration session
 * @param sequence durable session event sequence
 * @param revision semantic workflow revision
 * @param eventType stable event type
 * @param occurredAt original transaction event time
 * @param payload deterministic serialized payload
 */
public record WorkflowOutboxMessage(
    String eventId,
    String sessionId,
    long sequence,
    long revision,
    String eventType,
    Instant occurredAt,
    String payload) {

  public WorkflowOutboxMessage {
    eventId = requireNotBlank(eventId, "eventId");
    sessionId = requireNotBlank(sessionId, "sessionId");
    if (sequence <= 0 || revision <= 0) {
      throw new IllegalArgumentException("sequence and revision must be > 0");
    }
    eventType = requireNotBlank(eventType, "eventType");
    Objects.requireNonNull(occurredAt, "occurredAt");
    payload = requireNotBlank(payload, "payload");
  }

  /** Creates a publication envelope without exposing lease or retry metadata. */
  public static WorkflowOutboxMessage from(StoredWorkflowOutboxEntry entry) {
    StoredWorkflowOutboxEntry requiredEntry = Objects.requireNonNull(entry, "entry");
    return new WorkflowOutboxMessage(
        requiredEntry.eventId(),
        requiredEntry.sessionId(),
        requiredEntry.sequence(),
        requiredEntry.revision(),
        requiredEntry.eventType(),
        requiredEntry.occurredAt(),
        requiredEntry.payload());
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
