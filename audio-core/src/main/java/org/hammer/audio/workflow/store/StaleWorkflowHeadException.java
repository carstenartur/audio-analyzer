package org.hammer.audio.workflow.store;

/** Raised when a conditional workflow commit observes a different branch HEAD. */
public final class StaleWorkflowHeadException extends RuntimeException {

  private final String branch;
  private final CommitId expectedHead;
  private final CommitId actualHead;

  /**
   * Creates a typed optimistic-concurrency conflict.
   *
   * @param branch branch whose HEAD changed
   * @param expectedHead HEAD observed by the caller, or {@code null} for a missing branch
   * @param actualHead current HEAD, or {@code null} when the branch is missing
   */
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

  /** Returns the branch involved in the conflict. */
  public String branch() {
    return branch;
  }

  /** Returns the HEAD expected by the caller, or {@code null}. */
  public CommitId expectedHead() {
    return expectedHead;
  }

  /** Returns the HEAD observed by the store, or {@code null}. */
  public CommitId actualHead() {
    return actualHead;
  }

  private static String value(CommitId commitId) {
    return commitId == null ? "<missing>" : commitId.value();
  }
}
