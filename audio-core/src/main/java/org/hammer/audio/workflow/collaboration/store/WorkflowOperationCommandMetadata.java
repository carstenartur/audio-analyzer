package org.hammer.audio.workflow.collaboration.store;

import java.util.Objects;

/**
 * Durable command relation for one accepted semantic operation.
 *
 * @param kind command category
 * @param commandId stable idempotency id of the client/server command
 * @param targetOperationId operation targeted by undo or redo, otherwise {@code null}
 */
public record WorkflowOperationCommandMetadata(
    Kind kind, String commandId, String targetOperationId) {

  /** Durable semantic command categories. */
  public enum Kind {
    NORMAL,
    UNDO,
    REDO
  }

  public WorkflowOperationCommandMetadata {
    Objects.requireNonNull(kind, "kind");
    commandId = requireNotBlank(commandId, "commandId");
    if (kind == Kind.NORMAL) {
      if (targetOperationId != null && !targetOperationId.isBlank()) {
        throw new IllegalArgumentException("normal command must not target an operation");
      }
      targetOperationId = null;
    } else {
      targetOperationId = requireNotBlank(targetOperationId, "targetOperationId");
    }
  }

  /** Metadata for an ordinary forward operation. */
  public static WorkflowOperationCommandMetadata normal(String operationId) {
    return new WorkflowOperationCommandMetadata(Kind.NORMAL, operationId, null);
  }

  /** Metadata for an undo command and its target operation. */
  public static WorkflowOperationCommandMetadata undo(String commandId, String targetOperationId) {
    return new WorkflowOperationCommandMetadata(Kind.UNDO, commandId, targetOperationId);
  }

  /** Metadata for a redo command and its target undo operation. */
  public static WorkflowOperationCommandMetadata redo(
      String commandId, String targetUndoOperationId) {
    return new WorkflowOperationCommandMetadata(Kind.REDO, commandId, targetUndoOperationId);
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
