package org.hammer.audio.workflow.collaboration;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable server-computed undo preview.
 *
 * @param previewId identity bound to session revision and selected target
 * @param targetOperationId selected operation
 * @param targetActorId original operation actor
 * @param operationType semantic operation type
 * @param targetOccurredAt target operation occurrence timestamp
 * @param affectedObjectIds semantic objects affected by the target
 * @param revision revision at which the preview is valid
 * @param blockingOperations later conflicting operations, empty when undo is safe
 */
public record WorkflowUndoPreview(
    String previewId,
    String targetOperationId,
    String targetActorId,
    String operationType,
    Instant targetOccurredAt,
    List<String> affectedObjectIds,
    long revision,
    List<BlockingOperation> blockingOperations) {

  public WorkflowUndoPreview {
    previewId = requireNotBlank(previewId, "previewId");
    targetOperationId = requireNotBlank(targetOperationId, "targetOperationId");
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

  /** Returns whether no later operation blocks the inverse. */
  public boolean safe() {
    return blockingOperations.isEmpty();
  }

  /**
   * Later operation that intersects the selected target's semantic objects.
   *
   * @param operationId stable id of the later blocking operation
   * @param actorId actor that authored the blocking operation
   * @param conflictingObjectIds semantic object ids shared with the target operation
   */
  public record BlockingOperation(
      String operationId, String actorId, List<String> conflictingObjectIds)
      implements Serializable {

    @Serial private static final long serialVersionUID = 1L;

    public BlockingOperation {
      operationId = requireNotBlank(operationId, "operationId");
      actorId = requireNotBlank(actorId, "actorId");
      conflictingObjectIds =
          List.copyOf(Objects.requireNonNull(conflictingObjectIds, "conflictingObjectIds"));
      if (conflictingObjectIds.isEmpty()) {
        throw new IllegalArgumentException("conflictingObjectIds must not be empty");
      }
    }
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
