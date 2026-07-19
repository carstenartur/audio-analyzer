package org.hammer.audio.workflow.collaboration;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable server-computed redo preview.
 *
 * @param previewId identity bound to session revision and selected undo target
 * @param targetUndoOperationId selected undo operation
 * @param targetActorId actor that authored the undo operation
 * @param operationType semantic operation type stored for the undo operation
 * @param targetOccurredAt undo operation occurrence timestamp
 * @param affectedObjectIds semantic objects affected by the redo target
 * @param revision revision at which the preview is valid
 * @param blockingOperations later conflicting operations, empty when redo is safe
 */
public record WorkflowRedoPreview(
    String previewId,
    String targetUndoOperationId,
    String targetActorId,
    String operationType,
    Instant targetOccurredAt,
    List<String> affectedObjectIds,
    long revision,
    List<WorkflowUndoPreview.BlockingOperation> blockingOperations) {

  public WorkflowRedoPreview {
    previewId = requireNotBlank(previewId, "previewId");
    targetUndoOperationId = requireNotBlank(targetUndoOperationId, "targetUndoOperationId");
    targetActorId = requireNotBlank(targetActorId, "targetActorId");
    operationType = requireNotBlank(operationType, "operationType");
    Objects.requireNonNull(targetOccurredAt, "targetOccurredAt");
    affectedObjectIds = List.copyOf(Objects.requireNonNull(affectedObjectIds, "affectedObjectIds"));
    if (revision < 0) {
      throw new IllegalArgumentException("revision must be >= 0");
    }
    blockingOperations =
        List.copyOf(Objects.requireNonNull(blockingOperations, "blockingOperations"));
  }

  /** Returns whether no later operation blocks the redo. */
  public boolean safe() {
    return blockingOperations.isEmpty();
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
