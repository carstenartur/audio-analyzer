package org.hammer.audio.workflow.history;

import java.util.Objects;
import org.hammer.audio.workflow.store.CommitId;

/**
 * Creates a new workflow branch from one exact commit reachable from a source branch.
 *
 * @param sourceBranch reachability boundary for the selected commit
 * @param newBranch new branch name that must not already exist
 * @param fromCommit exact stored commit used as the new branch HEAD
 */
public record CreateWorkflowBranchCommand(
    String sourceBranch, String newBranch, CommitId fromCommit) {

  public CreateWorkflowBranchCommand {
    sourceBranch = requireBranch(sourceBranch, "sourceBranch");
    newBranch = requireBranch(newBranch, "newBranch");
    Objects.requireNonNull(fromCommit, "fromCommit");
    if (sourceBranch.equals(newBranch)) {
      throw new IllegalArgumentException("newBranch must differ from sourceBranch");
    }
  }

  private static String requireBranch(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }
}
