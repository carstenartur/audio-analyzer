package org.hammer.audio.workflow.collaboration;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable read-only descriptor for one accepted semantic workflow operation.
 *
 * @param operationId stable operation identifier
 * @param operationType semantic operation type
 * @param actorId actor that authored the operation
 * @param occurredAt operation occurrence timestamp
 * @param revision semantic revision produced by the operation
 * @param sequence durable event sequence assigned to the operation
 * @param commandKind normal, undo or redo command category
 * @param commandId stable idempotency identity of the command
 * @param targetOperationId target operation for undo or redo, otherwise {@code null}
 * @param affectedObjectIds semantic objects affected by the operation
 * @param reconstructible whether a complete operation body is available
 * @param activeUndoTarget whether the operation is currently an active undo target
 * @param activeRedoTarget whether the operation is currently an active redo target
 */
public record WorkflowHistoryDescriptor(
    String operationId,
    String operationType,
    String actorId,
    Instant occurredAt,
    long revision,
    long sequence,
    CommandKind commandKind,
    String commandId,
    String targetOperationId,
    List<String> affectedObjectIds,
    boolean reconstructible,
    boolean activeUndoTarget,
    boolean activeRedoTarget) {

  /** Stable public command categories independent of persistence implementation types. */
  public enum CommandKind {
    NORMAL,
    UNDO,
    REDO
  }

  public WorkflowHistoryDescriptor {
    operationId = requireNotBlank(operationId, "operationId");
    operationType = requireNotBlank(operationType, "operationType");
    actorId = requireNotBlank(actorId, "actorId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    if (revision <= 0 || sequence <= 0) {
      throw new IllegalArgumentException("revision and sequence must be > 0");
    }
    Objects.requireNonNull(commandKind, "commandKind");
    commandId = requireNotBlank(commandId, "commandId");
    if (commandKind == CommandKind.NORMAL) {
      if (targetOperationId != null && !targetOperationId.isBlank()) {
        throw new IllegalArgumentException("normal history entry must not have a target");
      }
      targetOperationId = null;
    } else {
      targetOperationId = requireNotBlank(targetOperationId, "targetOperationId");
    }
    affectedObjectIds = List.copyOf(Objects.requireNonNull(affectedObjectIds, "affectedObjectIds"));
  }

  private static String requireNotBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
