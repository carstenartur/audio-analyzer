package org.hammer.audio.workflow.collaboration.store;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.workflow.WorkflowOperation;

/** Converts semantic workflow operations into deterministic durable persistence data. */
public final class WorkflowOperationPersistenceCodec {

  private WorkflowOperationPersistenceCodec() {
    // Utility class.
  }

  /** Encodes one ordinary forward operation. */
  public static WorkflowOperationPersistenceData encode(WorkflowOperation operation) {
    WorkflowOperation requiredOperation = Objects.requireNonNull(operation, "operation");
    return encode(
        requiredOperation,
        WorkflowOperationCommandMetadata.normal(requiredOperation.operationId()));
  }

  /**
   * Encodes one operation for durable append, restart-safe idempotency and semantic reconstruction.
   *
   * @param operation semantic workflow operation
   * @param command durable normal/undo/redo command relation
   * @return deterministic persistence data
   */
  public static WorkflowOperationPersistenceData encode(
      WorkflowOperation operation, WorkflowOperationCommandMetadata command) {
    WorkflowOperation requiredOperation = Objects.requireNonNull(operation, "operation");
    WorkflowOperationBodyCodec.EncodedBody body =
        WorkflowOperationBodyCodec.encode(requiredOperation);
    return new WorkflowOperationPersistenceData(
        requiredOperation.operationId(),
        requiredOperation.author(),
        requiredOperation.getClass().getSimpleName(),
        requiredOperation.timestamp(),
        encodeSemanticIdentity(requiredOperation),
        body.version(),
        body.body(),
        Objects.requireNonNull(command, "command"));
  }

  /**
   * Compares a durable operation with a retry candidate without applying it again.
   *
   * <p>The timestamp is intentionally excluded from the legacy fingerprint to preserve the existing
   * command-idempotency contract. New rows additionally compare the complete operation body after
   * normalizing the candidate to the stored timestamp, because a transport retry may recreate the
   * same semantic operation with a new timestamp.
   *
   * @param stored durable accepted operation
   * @param candidate semantic retry candidate
   * @return whether both describe the same accepted semantic command
   */
  public static boolean matches(StoredWorkflowOperation stored, WorkflowOperation candidate) {
    StoredWorkflowOperation requiredStored = Objects.requireNonNull(stored, "stored");
    WorkflowOperationPersistenceData encodedCandidate = encode(candidate, requiredStored.command());
    boolean identityMatches =
        requiredStored.operationId().equals(encodedCandidate.operationId())
            && requiredStored.actorId().equals(encodedCandidate.actorId())
            && requiredStored.operationType().equals(encodedCandidate.operationType())
            && requiredStored.payload().equals(encodedCandidate.payload());
    if (!identityMatches || !requiredStored.hasOperationBody()) {
      return identityMatches;
    }
    WorkflowOperation normalizedCandidate =
        WorkflowOperationBodyCodec.reidentify(
            candidate, candidate.operationId(), requiredStored.occurredAt(), candidate.author());
    WorkflowOperationBodyCodec.EncodedBody normalizedBody =
        WorkflowOperationBodyCodec.encode(normalizedCandidate);
    return requiredStored.bodyVersion() == normalizedBody.version()
        && requiredStored.operationBody().equals(normalizedBody.body());
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
