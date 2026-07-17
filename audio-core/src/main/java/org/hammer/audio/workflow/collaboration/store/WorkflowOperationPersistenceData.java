package org.hammer.audio.workflow.collaboration.store;

import java.time.Instant;
import java.util.Objects;

/**
 * Canonically serialized semantic operation supplied to the durable session store.
 *
 * @param operationId stable idempotency identifier for the operation
 * @param actorId actor that authored the operation
 * @param operationType stable semantic operation type
 * @param occurredAt operation occurrence timestamp
 * @param payload deterministic serialized operation payload
 */
public record WorkflowOperationPersistenceData(
    String operationId, String actorId, String operationType, Instant occurredAt, String payload) {

  public WorkflowOperationPersistenceData {
    operationId = requireNotBlank(operationId, "operationId");
    actorId = requireNotBlank(actorId, "actorId");
    operationType = requireNotBlank(operationType, "operationType");
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
