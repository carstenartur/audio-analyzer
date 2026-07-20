package org.hammer.audio.workflow.history;

/** Raised when an explicit history command conflicts with active application access state. */
public final class WorkflowHistoryAccessException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String storedBranch;
  private final String storedWorkflowId;

  /**
   * Creates a typed history-command access conflict.
   *
   * @param branch branch targeted by the command
   * @param workflowId workflow whose active state blocks the command
   * @param message human-readable conflict explanation
   */
  public WorkflowHistoryAccessException(String branch, String workflowId, String message) {
    super(message);
    this.storedBranch = branch;
    this.storedWorkflowId = workflowId;
  }

  /** Returns the branch targeted by the blocked command. */
  public String branch() {
    return storedBranch;
  }

  /** Returns the workflow whose active state blocked the command. */
  public String workflowId() {
    return storedWorkflowId;
  }
}
