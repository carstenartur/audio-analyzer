package org.hammer.audio.workflow.collaboration.store;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.hammer.audio.workflow.WorkflowOperation;

/**
 * Immutable accepted operation read back from durable collaboration history.
 *
 * @param sessionId owning collaboration-session identifier
 * @param operationId stable idempotency identifier for the semantic operation
 * @param actorId actor that authored the operation
 * @param operationType stable semantic operation type
 * @param occurredAt operation occurrence timestamp
 * @param sequence durable session event sequence
 * @param revision semantic revision produced by the operation
 * @param payload deterministic serialized identity payload
 * @param bodyVersion version of the reconstructible operation body, or zero for a legacy row
 * @param operationBody complete operation body, or {@code null} for a legacy row
 */
public record StoredWorkflowOperation(
    String sessionId,
    String operationId,
    String actorId,
    String operationType,
    Instant occurredAt,
    long sequence,
    long revision,
    String payload,
    int bodyVersion,
    String operationBody) {

  public StoredWorkflowOperation {
    sessionId = requireNotBlank(sessionId, "sessionId");
    operationId = requireNotBlank(operationId, "operationId");
    actorId = requireNotBlank(actorId, "actorId");
    operationType = requireNotBlank(operationType, "operationType");
    Objects.requireNonNull(occurredAt, "occurredAt");
    requirePositive(sequence, "sequence");
    requirePositive(revision, "revision");
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
  }

  /** Creates a legacy stored operation without a reconstructible body. */
  public StoredWorkflowOperation(
      String sessionId,
      String operationId,
      String actorId,
      String operationType,
      Instant occurredAt,
      long sequence,
      long revision,
      String payload) {
    this(
        sessionId,
        operationId,
        actorId,
        operationType,
        occurredAt,
        sequence,
        revision,
        payload,
        0,
        null);
  }

  /** Returns whether this operation can be reconstructed for semantic undo/redo. */
  public boolean hasOperationBody() {
    return bodyVersion > 0;
  }

  /** Reconstructs the operation when a versioned body is available. */
  public Optional<WorkflowOperation> operation() {
    return hasOperationBody()
        ? Optional.of(WorkflowOperationBodyCodec.decode(bodyVersion, operationBody))
        : Optional.empty();
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
