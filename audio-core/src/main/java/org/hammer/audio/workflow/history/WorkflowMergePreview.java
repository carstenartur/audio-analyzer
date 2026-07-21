package org.hammer.audio.workflow.history;

import java.util.Objects;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.Preview;
import org.hammer.audio.workflow.store.CommitId;

/**
 * Authoritative stored workflows and deterministic semantic merge preview.
 *
 * @param targetBranch branch containing the local version
 * @param remoteBranch branch containing the remote version
 * @param baseCommit exact common-base commit
 * @param localCommit exact local commit
 * @param remoteCommit exact remote commit
 * @param baseWorkflow parsed base workflow
 * @param localWorkflow parsed local workflow
 * @param remoteWorkflow parsed remote workflow
 * @param merge deterministic automatic result, conflicts and validation impact
 */
public record WorkflowMergePreview(
    String targetBranch,
    String remoteBranch,
    CommitId baseCommit,
    CommitId localCommit,
    CommitId remoteCommit,
    Workflow baseWorkflow,
    Workflow localWorkflow,
    Workflow remoteWorkflow,
    Preview merge) {

  public WorkflowMergePreview {
    requireText(targetBranch, "targetBranch");
    requireText(remoteBranch, "remoteBranch");
    Objects.requireNonNull(baseCommit, "baseCommit");
    Objects.requireNonNull(localCommit, "localCommit");
    Objects.requireNonNull(remoteCommit, "remoteCommit");
    Objects.requireNonNull(baseWorkflow, "baseWorkflow");
    Objects.requireNonNull(localWorkflow, "localWorkflow");
    Objects.requireNonNull(remoteWorkflow, "remoteWorkflow");
    Objects.requireNonNull(merge, "merge");
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
