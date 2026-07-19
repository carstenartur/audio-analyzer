package org.hammer.audio.workflow.collaboration.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.workflow.WorkflowOperation;

/** Converts semantic workflow operations into deterministic durable persistence data. */
public final class WorkflowOperationPersistenceCodec {

  private static final int MAX_AFFECTED_OBJECTS = 1_000_000;

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

  /**
   * Decodes the affected-object prefix retained by legacy semantic identity payloads.
   *
   * <p>The remaining operation-specific payload map is deliberately ignored. This method exists so
   * read-only history can still describe legacy rows without claiming that their complete semantic
   * bodies are reconstructible.
   *
   * @param payload deterministic semantic identity payload
   * @return immutable affected object identifiers in their original order
   */
  public static List<String> decodeAffectedObjectIds(String payload) {
    String requiredPayload = Objects.requireNonNull(payload, "payload");
    Cursor cursor = new Cursor(requiredPayload);
    int size = cursor.readNonNegativeLength("affected object count");
    if (size > MAX_AFFECTED_OBJECTS) {
      throw new IllegalArgumentException("affected object count is too large: " + size);
    }
    List<String> affectedObjectIds = new ArrayList<>(size);
    for (int index = 0; index < size; index++) {
      affectedObjectIds.add(cursor.readValue("affected object id"));
    }
    return List.copyOf(affectedObjectIds);
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

  private static final class Cursor {
    private final String value;
    private int offset;

    Cursor(String value) {
      this.value = value;
    }

    int readNonNegativeLength(String description) {
      int delimiter = value.indexOf(':', offset);
      if (delimiter < 0) {
        throw malformed(description);
      }
      int parsed;
      try {
        parsed = Integer.parseInt(value.substring(offset, delimiter));
      } catch (NumberFormatException exception) {
        throw new IllegalArgumentException(
            "Invalid " + description + " in semantic identity", exception);
      }
      if (parsed < 0) {
        throw malformed(description);
      }
      offset = delimiter + 1;
      return parsed;
    }

    String readValue(String description) {
      int length = readNonNegativeLength(description + " length");
      int end;
      try {
        end = Math.addExact(offset, length);
      } catch (ArithmeticException exception) {
        throw new IllegalArgumentException(
            "Invalid " + description + " in semantic identity", exception);
      }
      if (end > value.length()) {
        throw malformed(description);
      }
      String parsed = value.substring(offset, end);
      offset = end;
      return parsed;
    }

    private IllegalArgumentException malformed(String description) {
      return new IllegalArgumentException("Malformed " + description + " in semantic identity");
    }
  }
}
