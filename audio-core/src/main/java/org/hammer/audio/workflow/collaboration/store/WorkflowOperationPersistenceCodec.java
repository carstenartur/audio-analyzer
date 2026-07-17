package org.hammer.audio.workflow.collaboration.store;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.hammer.audio.workflow.WorkflowOperation;

/** Converts semantic workflow operations into deterministic durable identity data. */
public final class WorkflowOperationPersistenceCodec {

  private WorkflowOperationPersistenceCodec() {}

  /**
   * Encodes one operation for durable append and restart-safe idempotency checks.
   *
   * @param operation semantic workflow operation
   * @return deterministic persistence data
   */
  public static WorkflowOperationPersistenceData encode(WorkflowOperation operation) {
    WorkflowOperation requiredOperation = Objects.requireNonNull(operation, "operation");
    return new WorkflowOperationPersistenceData(
        requiredOperation.operationId(),
        requiredOperation.author(),
        requiredOperation.getClass().getSimpleName(),
        requiredOperation.timestamp(),
        encodePayload(requiredOperation.payload()));
  }

  /**
   * Compares a durable operation with a retry candidate without applying it again.
   *
   * @param stored durable accepted operation
   * @param candidate semantic retry candidate
   * @return whether both describe the same accepted semantic command
   */
  public static boolean matches(StoredWorkflowOperation stored, WorkflowOperation candidate) {
    StoredWorkflowOperation requiredStored = Objects.requireNonNull(stored, "stored");
    WorkflowOperationPersistenceData encodedCandidate = encode(candidate);
    return requiredStored.operationId().equals(encodedCandidate.operationId())
        && requiredStored.actorId().equals(encodedCandidate.actorId())
        && requiredStored.operationType().equals(encodedCandidate.operationType())
        && requiredStored.occurredAt().equals(encodedCandidate.occurredAt())
        && requiredStored.payload().equals(encodedCandidate.payload());
  }

  /**
   * Encodes a semantic payload map in stable key order using unambiguous length prefixes.
   *
   * @param payload semantic operation payload
   * @return deterministic encoded payload
   */
  public static String encodePayload(Map<String, String> payload) {
    Map<String, String> sorted = new TreeMap<>(Objects.requireNonNull(payload, "payload"));
    StringBuilder encoded = new StringBuilder();
    for (Map.Entry<String, String> entry : sorted.entrySet()) {
      appendValue(encoded, entry.getKey());
      appendValue(encoded, entry.getValue());
    }
    return encoded.toString();
  }

  private static void appendValue(StringBuilder encoded, String value) {
    if (value == null) {
      encoded.append("-1:");
      return;
    }
    encoded.append(value.length()).append(':').append(value);
  }
}
