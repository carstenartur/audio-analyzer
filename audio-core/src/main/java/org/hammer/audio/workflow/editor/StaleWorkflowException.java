package org.hammer.audio.workflow.editor;

/** A confirmation described an older workflow and cannot authorize replacing the current one. */
public final class StaleWorkflowException extends IllegalStateException {
  private static final long serialVersionUID = 1L;

  /** Create a stale-preview precondition failure. */
  public StaleWorkflowException() {
    super(
        "The current workflow changed after preview; inspect the new semantic diff before"
            + " applying");
  }
}
