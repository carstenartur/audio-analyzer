package org.hammer.audio.workflow.collaboration.store;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.workflow.WorkflowOperation;

/** Converts semantic workflow operations into deterministic durable identity data. */
public final class WorkflowOperationPersistenceCodec {

  private WorkflowOperationPersistenceCodec() {
    // Utility class.
  }

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
        encodeSemanticIdentity(requiredOperation));
  }

  /**
   * Compares a durable operation with a retry candidate without applying it again.
   *
   * <p>The timestamp is intentionally excluded to preserve the existing command-idempotency
   * contract: a transport retry may recreate an otherwise identical operation with a new timestamp.
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
        && requiredStored.payload().equals(encodedCandidate.payload());
  }

  /**
   * Encodes a semantic payload map in stable key order using unambiguous length prefixes.
   *
   * @param payload semantic operation payload
   * @return deterministic encoded payload
   */
  public static String encodePayload(Map<String, String> payload) {
    Map<String, String> requiredPayload = Objects.requireNonNull(payload, "payload");
    StringBuilder encoded = new StringBuilder();
    requiredPayload.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              appendValue(encoded, entry.getKey());
              appendValue(encoded, entry.getValue());
            });
    return encoded.toString();
  }

  private static String encodeSemanticIdentity(WorkflowOperation operation) {
    StringBuilder encoded = new StringBuilder();
    appendList(encoded, operation.affectedObjectIds());
    encoded.append(encodePayload(operation.payload()));
    return encoded.toString();
  }

  private static void appendList(StringBuilder encoded, List<String> values) {
    List<String> requiredValues = List.copyOf(Objects.requireNonNull(values, "values"));
    encoded.append(requiredValues.size()).append(':');
    for (String value : requiredValues) {
      appendValue(encoded, value);
    }
  }

  private static void appendValue(StringBuilder encoded, String value) {
    if (value == null) {
      encoded.append("-1:");
      return;
    }
    encoded.append(value.length()).append(':').append(value);
  }
}
