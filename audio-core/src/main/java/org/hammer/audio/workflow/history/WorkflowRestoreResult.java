package org.hammer.audio.workflow.history;

import java.util.Objects;
import org.hammer.audio.workflow.store.CommitId;

/**
 * Audit evidence returned after a historical workflow was restored as a new commit.
 *
 * @param branch restored branch
 * @param targetCommit historical source commit
 * @param previousHead HEAD protected by optimistic concurrency
 * @param restoredCommit newly created audit commit
 */
public record WorkflowRestoreResult(
    String branch, CommitId targetCommit, CommitId previousHead, CommitId restoredCommit) {

  public WorkflowRestoreResult {
    branch = Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(targetCommit, "targetCommit");
    Objects.requireNonNull(previousHead, "previousHead");
    Objects.requireNonNull(restoredCommit, "restoredCommit");
  }
}
