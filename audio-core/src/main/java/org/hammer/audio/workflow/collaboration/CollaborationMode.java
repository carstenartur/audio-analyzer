package org.hammer.audio.workflow.collaboration;

/** Collaboration/session mode for workflow editing. */
public enum CollaborationMode {
  PRIVATE_WORKSPACE(UndoScope.PERSONAL),
  SHARED_SESSION_PERSONAL_UNDO(UndoScope.PERSONAL),
  SHARED_SESSION_SHARED_UNDO(UndoScope.SHARED);

  private final UndoScope configuredUndoScope;

  CollaborationMode(UndoScope undoScope) {
    this.configuredUndoScope = undoScope;
  }

  public UndoScope undoScope() {
    return configuredUndoScope;
  }
}
