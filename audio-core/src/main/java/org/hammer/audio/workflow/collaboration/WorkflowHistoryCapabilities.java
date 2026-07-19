package org.hammer.audio.workflow.collaboration;

import java.util.List;
import java.util.Objects;

/**
 * Actor-scoped read-only undo and redo capabilities at one semantic revision.
 *
 * @param mode immutable collaboration mode
 * @param revision semantic revision at which the capabilities were computed
 * @param personalUndoPermitted whether this mode permits personal undo
 * @param personalUndo current personal undo target, or {@code null} when none exists
 * @param redo current actor-owned redo target, or {@code null} when none exists
 * @param sharedUndoPermitted whether this mode permits explicit shared-target undo
 */
public record WorkflowHistoryCapabilities(
    CollaborationMode mode,
    long revision,
    boolean personalUndoPermitted,
    Action personalUndo,
    Action redo,
    boolean sharedUndoPermitted) {

  /** Current availability of a server-selected history action. */
  public enum ActionStatus {
    AVAILABLE,
    BLOCKED,
    NOT_RECONSTRUCTIBLE
  }

  /**
   * One current actor-scoped undo or redo target with authoritative conflict information.
   *
   * @param operation selected immutable history entry
   * @param status current action availability
   * @param blockingOperations later operations that block the action
   */
  public record Action(
      WorkflowHistoryDescriptor operation,
      ActionStatus status,
      List<WorkflowUndoPreview.BlockingOperation> blockingOperations) {

    public Action {
      Objects.requireNonNull(operation, "operation");
      Objects.requireNonNull(status, "status");
      blockingOperations =
          List.copyOf(Objects.requireNonNull(blockingOperations, "blockingOperations"));
      if (status == ActionStatus.AVAILABLE && !blockingOperations.isEmpty()) {
        throw new IllegalArgumentException("available action must not contain blockers");
      }
      if (status == ActionStatus.BLOCKED && blockingOperations.isEmpty()) {
        throw new IllegalArgumentException("blocked action requires blocking operations");
      }
    }

    /** Returns whether the server currently considers this action executable. */
    public boolean available() {
      return status == ActionStatus.AVAILABLE;
    }
  }

  public WorkflowHistoryCapabilities {
    Objects.requireNonNull(mode, "mode");
    if (revision < 0) {
      throw new IllegalArgumentException("revision must be >= 0");
    }
    if (!personalUndoPermitted && personalUndo != null) {
      throw new IllegalArgumentException("personal undo target requires a permitting mode");
    }
  }
}
