package org.hammer.audio.workflow.collaboration.store;

import java.time.Instant;
import java.util.Objects;

/** Immutable accepted operation read back from durable collaboration history. */
public record StoredWorkflowOperation(
    String sessionId,
    String operationId,
    String actorId,
    String operationType,
    Instant occurredAt,
    long sequence,
    long revision,
    String payload) {

  public StoredWorkflowOperation {
    sessionId = requireNotBlank(sessionId, "sessionId");
    operationId = requireNotBlank(operationId, "operationId");
    actorId = requireNotBlank(actorId, "actorId");
    operationType = requireNotBlank(operationType, "operationType");
    Objects.requireNonNull(occurredAt, "occurredAt");
    requirePositive(sequence, "sequence");
    requirePositive(revision, "revision");
    payload = requireNotBlank(payload, "payload");
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
