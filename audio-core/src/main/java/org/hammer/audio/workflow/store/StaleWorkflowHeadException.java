package org.hammer.audio.workflow.store;

/** Raised when a conditional workflow commit observes a different branch HEAD. */
public final class StaleWorkflowHeadException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String branch;
  private final String expectedHeadValue;
  private final String actualHeadValue;

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
            + displayValue(expectedHead)
            + "; current HEAD is "
            + displayValue(actualHead));
    this.branch = branch;
    this.expectedHeadValue = storedValue(expectedHead);
    this.actualHeadValue = storedValue(actualHead);
  }

  /** Returns the branch involved in the conflict. */
  public String branch() {
    return branch;
  }

  /** Returns the HEAD expected by the caller, or {@code null}. */
  public CommitId expectedHead() {
    return commitId(expectedHeadValue);
  }

  /** Returns the HEAD observed by the store, or {@code null}. */
  public CommitId actualHead() {
    return commitId(actualHeadValue);
  }

  private static CommitId commitId(String value) {
    return value == null ? null : new CommitId(value);
  }

  private static String storedValue(CommitId commitId) {
    return commitId == null ? null : commitId.value();
  }

  private static String displayValue(CommitId commitId) {
    return commitId == null ? "<missing>" : commitId.value();
  }
}
