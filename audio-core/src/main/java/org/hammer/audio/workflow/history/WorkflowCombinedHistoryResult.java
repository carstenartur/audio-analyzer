package org.hammer.audio.workflow.history;

import java.util.Objects;

/**
 * Generic and workflow-semantic evidence for one exact matching commit.
 *
 * @param commit generic commit metadata, changed paths and exact identity
 * @param semantics branch-specific workflow, node and property evidence
 */
public record WorkflowCombinedHistoryResult(
    WorkflowHistoryTextResult commit, WorkflowSemanticHistoryResult semantics) {

  public WorkflowCombinedHistoryResult {
    Objects.requireNonNull(commit, "commit");
    Objects.requireNonNull(semantics, "semantics");
    if (!commit.commitId().equals(semantics.commitId())) {
      throw new IllegalArgumentException("generic and semantic evidence must reference one commit");
    }
  }
}
