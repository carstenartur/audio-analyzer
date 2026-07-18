package org.hammer.audio.workflow.collaboration;

import java.util.List;
import java.util.Objects;

/** Machine-readable semantic conflict raised when a later operation blocks undo or redo. */
public final class WorkflowUndoConflictException extends WorkflowSessionException {

  private final String targetOperationId;
  private final List<WorkflowUndoPreview.BlockingOperation> blockingOperations;

  /** Creates a conflict tied to one target and its later blockers. */
  public WorkflowUndoConflictException(
      String sessionId,
      String targetOperationId,
      List<WorkflowUndoPreview.BlockingOperation> blockingOperations) {
    super(
        Code.UNDO_CONFLICT,
        requireNotBlank(sessionId, "sessionId"),
        "Semantic history command is blocked by later operations for target "
            + targetOperationId);
    this.targetOperationId = requireNotBlank(targetOperationId, "targetOperationId");
    this.blockingOperations =
        List.copyOf(Objects.requireNonNull(blockingOperations, "blockingOperations"));
    if (this.blockingOperations.isEmpty()) {
      throw new IllegalArgumentException("blockingOperations must not be empty");
    }
  }

  /** Operation whose semantic inverse was requested. */
  public String targetOperationId() {
    return targetOperationId;
  }

  /** Later operations intersecting the target's affected semantic objects. */
  public List<WorkflowUndoPreview.BlockingOperation> blockingOperations() {
    return blockingOperations;
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
