package org.hammer.audio.workflow.history;

/** Raised when an explicit history command conflicts with active application access state. */
public final class WorkflowHistoryAccessException extends RuntimeException {

  private final String branch;
  private final String workflowId;

  /**
   * Creates a typed history-command access conflict.
   *
   * @param branch branch targeted by the command
   * @param workflowId workflow whose active state blocks the command
   * @param message human-readable conflict explanation
   */
  public WorkflowHistoryAccessException(String branch, String workflowId, String message) {
    super(message);
    this.branch = branch;
    this.workflowId = workflowId;
  }

  /** Returns the branch targeted by the blocked command. */
  public String branch() {
    return branch;
  }

  /** Returns the workflow whose active state blocked the command. */
  public String workflowId() {
    return workflowId;
  }
}
