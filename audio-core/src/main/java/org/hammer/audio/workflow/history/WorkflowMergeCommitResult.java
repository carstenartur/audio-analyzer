package org.hammer.audio.workflow.history;

import java.util.Objects;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.store.CommitId;

/**
 * Evidence for one validated semantic merge checkpoint.
 *
 * @param targetBranch branch advanced by the merge
 * @param baseCommit exact common-base commit
 * @param localCommit exact previous local HEAD
 * @param remoteCommit exact remote source commit
 * @param mergedCommit newly created checkpoint commit
 * @param workflow validated deterministic merged workflow
 * @param auditMessage complete deterministic commit message including merge-resolution metadata
 */
public record WorkflowMergeCommitResult(
    String targetBranch,
    CommitId baseCommit,
    CommitId localCommit,
    CommitId remoteCommit,
    CommitId mergedCommit,
    Workflow workflow,
    String auditMessage) {

  public WorkflowMergeCommitResult {
    if (targetBranch == null || targetBranch.isBlank()) {
      throw new IllegalArgumentException("targetBranch must not be blank");
    }
    Objects.requireNonNull(baseCommit, "baseCommit");
    Objects.requireNonNull(localCommit, "localCommit");
    Objects.requireNonNull(remoteCommit, "remoteCommit");
    Objects.requireNonNull(mergedCommit, "mergedCommit");
    Objects.requireNonNull(workflow, "workflow");
    if (auditMessage == null || auditMessage.isBlank()) {
      throw new IllegalArgumentException("auditMessage must not be blank");
    }
  }
}
