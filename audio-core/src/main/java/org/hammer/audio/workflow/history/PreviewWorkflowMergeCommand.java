package org.hammer.audio.workflow.history;

import java.util.Objects;
import org.hammer.audio.workflow.store.CommitId;

/**
 * Exact stored-version identities used to calculate one semantic three-way merge preview.
 *
 * @param targetBranch branch containing the local version and receiving a later merge commit
 * @param remoteBranch branch containing the remote version
 * @param baseCommit exact common-base commit reachable from both branches
 * @param localCommit exact local commit reachable from the target branch
 * @param remoteCommit exact remote commit reachable from the remote branch
 */
public record PreviewWorkflowMergeCommand(
    String targetBranch,
    String remoteBranch,
    CommitId baseCommit,
    CommitId localCommit,
    CommitId remoteCommit) {

  public PreviewWorkflowMergeCommand {
    targetBranch = requireBranch(targetBranch, "targetBranch");
    remoteBranch = requireBranch(remoteBranch, "remoteBranch");
    Objects.requireNonNull(baseCommit, "baseCommit");
    Objects.requireNonNull(localCommit, "localCommit");
    Objects.requireNonNull(remoteCommit, "remoteCommit");
  }

  private static String requireBranch(String branch, String name) {
    if (branch == null || branch.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return branch.trim();
  }
}
