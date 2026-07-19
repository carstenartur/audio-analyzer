package org.hammer.audio.workflow.collaboration;

import java.util.Objects;

/**
 * Server-side semantic undo command.
 *
 * @param commandId stable idempotency identifier
 * @param actor requesting actor
 * @param expectedRevision client-observed semantic revision
 * @param targetOperationId explicit target, required in shared-undo mode and optional otherwise
 * @param previewId immutable preview identity required in shared-undo mode
 */
public record UndoWorkflowCommand(
    String commandId,
    OperationActor actor,
    long expectedRevision,
    String targetOperationId,
    String previewId) {

  public UndoWorkflowCommand {
    commandId = requireNotBlank(commandId, "commandId");
    Objects.requireNonNull(actor, "actor");
    if (expectedRevision < 0) {
      throw new IllegalArgumentException("expectedRevision must be >= 0");
    }
    targetOperationId = normalize(targetOperationId);
    previewId = normalize(previewId);
  }

  private static String normalize(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
