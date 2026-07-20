package org.hammer.audio.workflow.store;

/** Raised when a conditional workflow commit observes a different branch HEAD. */
public final class StaleWorkflowHeadException extends RuntimeException {

  private final String branch;
  private final CommitId expectedHead;
  private final CommitId actualHead;

  /** Creates a typed optimistic-concurrency conflict. */
  public StaleWorkflowHeadException(String branch, CommitId expectedHead, CommitId actualHead) {
    super(
        "Workflow branch '"
            + branch
            + "' no longer points to expected commit "
            + value(expectedHead)
            + "; current HEAD is "
            + value(actualHead));
    this.branch = branch;
    this.expectedHead = expectedHead;
    this.actualHead = actualHead;
  }

  public String branch() {
    return branch;
  }

  public CommitId expectedHead() {
    return expectedHead;
  }

  public CommitId actualHead() {
    return actualHead;
  }

  private static String value(CommitId commitId) {
    return commitId == null ? "<missing>" : commitId.value();
  }
}
