package org.hammer.audio.workflow.collaboration;

import java.util.Objects;

/**
 * Server-side semantic redo command.
 *
 * @param commandId stable idempotency identifier
 * @param actor requesting actor
 * @param expectedRevision client-observed semantic revision
 * @param targetUndoOperationId accepted undo operation to invert
 */
public record RedoWorkflowCommand(
    String commandId, OperationActor actor, long expectedRevision, String targetUndoOperationId) {

  public RedoWorkflowCommand {
    commandId = requireNotBlank(commandId, "commandId");
    Objects.requireNonNull(actor, "actor");
    if (expectedRevision < 0) {
      throw new IllegalArgumentException("expectedRevision must be >= 0");
    }
    targetUndoOperationId = requireNotBlank(targetUndoOperationId, "targetUndoOperationId");
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
