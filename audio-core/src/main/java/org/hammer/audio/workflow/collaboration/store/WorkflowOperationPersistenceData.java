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
 * @param payload deterministic serialized identity payload
 * @param bodyVersion version of the reconstructible operation body, or zero for legacy data
 * @param operationBody complete deterministic operation body, or {@code null} for legacy data
 * @param command durable normal/undo/redo command relation
 */
public record WorkflowOperationPersistenceData(
    String operationId,
    String actorId,
    String operationType,
    Instant occurredAt,
    String payload,
    int bodyVersion,
    String operationBody,
    WorkflowOperationCommandMetadata command) {

  public WorkflowOperationPersistenceData {
    operationId = requireNotBlank(operationId, "operationId");
    actorId = requireNotBlank(actorId, "actorId");
    operationType = requireNotBlank(operationType, "operationType");
    Objects.requireNonNull(occurredAt, "occurredAt");
    payload = requireNotBlank(payload, "payload");
    if (bodyVersion < 0) {
      throw new IllegalArgumentException("bodyVersion must be >= 0");
    }
    if (bodyVersion == 0) {
      if (operationBody != null && !operationBody.isBlank()) {
        throw new IllegalArgumentException("legacy operation body must be absent");
      }
      operationBody = null;
    } else {
      operationBody = requireNotBlank(operationBody, "operationBody");
    }
    command = Objects.requireNonNull(command, "command");
  }

  /** Creates legacy identity-only persistence data for an ordinary operation. */
  public WorkflowOperationPersistenceData(
      String operationId,
      String actorId,
      String operationType,
      Instant occurredAt,
      String payload) {
    this(
        operationId,
        actorId,
        operationType,
        occurredAt,
        payload,
        0,
        null,
        WorkflowOperationCommandMetadata.normal(operationId));
  }

  /** Returns whether this persistence value contains a reconstructible body. */
  public boolean hasOperationBody() {
    return bodyVersion > 0;
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
