package org.hammer.audio.workflow.collaboration.store;

import java.time.Instant;
import java.util.Objects;

/**
 * Canonically serialized collaboration event inserted into the transactional outbox.
 *
 * @param eventId stable idempotency identifier for the event
 * @param eventType stable collaboration event type
 * @param occurredAt event occurrence timestamp
 * @param payload deterministic serialized event payload
 */
public record WorkflowOutboxEventData(
    String eventId, String eventType, Instant occurredAt, String payload) {

  public WorkflowOutboxEventData {
    eventId = requireNotBlank(eventId, "eventId");
    eventType = requireNotBlank(eventType, "eventType");
    Objects.requireNonNull(occurredAt, "occurredAt");
    payload = requireNotBlank(payload, "payload");
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
