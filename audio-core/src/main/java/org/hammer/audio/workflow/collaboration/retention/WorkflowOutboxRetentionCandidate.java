package org.hammer.audio.workflow.collaboration.retention;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable published-event candidate captured by one retention plan.
 *
 * @param eventId stable outbox event identifier
 * @param sessionId owning collaboration session identifier
 * @param publishedAt durable publication timestamp
 * @param reason eligibility reason evaluated when the plan was created
 */
public record WorkflowOutboxRetentionCandidate(
    String eventId,
    String sessionId,
    Instant publishedAt,
    WorkflowOutboxRetentionReason reason) {

  /** Validates the immutable candidate identity and eligibility evidence. */
  public WorkflowOutboxRetentionCandidate {
    eventId = requireNotBlank(eventId, "eventId");
    sessionId = requireNotBlank(sessionId, "sessionId");
    Objects.requireNonNull(publishedAt, "publishedAt");
    Objects.requireNonNull(reason, "reason");
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
