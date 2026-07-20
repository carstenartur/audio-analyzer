package org.hammer.audio.workflow.history;

/** Raised when an explicit history command conflicts with active application access state. */
public final class WorkflowHistoryAccessException extends RuntimeException {

  private final String branch;
  private final String workflowId;

  /** Creates a typed history-command access conflict. */
  public WorkflowHistoryAccessException(String branch, String workflowId, String message) {
    super(message);
    this.branch = branch;
    this.workflowId = workflowId;
  }

  public String branch() {
    return branch;
  }

  public String workflowId() {
    return workflowId;
  }
}
