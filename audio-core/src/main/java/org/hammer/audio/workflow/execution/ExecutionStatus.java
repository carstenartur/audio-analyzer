package org.hammer.audio.workflow.execution;

/**
 * Lifecycle status of a single workflow node or of an overall execution run.
 *
 * <p>Terminal statuses are {@link #COMPLETED}, {@link #FAILED}, {@link #SKIPPED} and {@link
 * #CANCELLED}.
 */
public enum ExecutionStatus {
  /** Not yet scheduled for execution. */
  IDLE,
  /** Waiting in the execution queue. */
  QUEUED,
  /** Currently executing. */
  RUNNING,
  /** Finished successfully. */
  COMPLETED,
  /** Finished with an error. */
  FAILED,
  /** Deliberately not executed (e.g. a predecessor failed). */
  SKIPPED,
  /** Stopped before completion. */
  CANCELLED
}
