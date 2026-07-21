package org.hammer.audio.workflow.history;

import java.util.Objects;
import org.hammer.audio.workflow.store.CommitId;

/**
 * Evidence for a newly created workflow branch.
 *
 * @param sourceBranch source reachability boundary
 * @param branch newly created branch
 * @param head exact initial branch HEAD
 * @param workflowId workflow stored at the initial HEAD
 */
public record WorkflowBranchCreationResult(
    String sourceBranch, String branch, CommitId head, String workflowId) {

  public WorkflowBranchCreationResult {
    requireText(sourceBranch, "sourceBranch");
    requireText(branch, "branch");
    Objects.requireNonNull(head, "head");
    requireText(workflowId, "workflowId");
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
