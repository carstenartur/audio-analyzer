package org.hammer.audio.workflow.editor;

/** Raised when a destructive load/import is attempted while the editor has unsaved changes. */
public final class DirtyWorkflowException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  /** Create the stable dirty-state failure. */
  public DirtyWorkflowException() {
    super("Current workflow has unsaved changes");
  }
}
