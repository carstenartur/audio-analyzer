package org.hammer.audio.workflow.history;

import java.util.Objects;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.store.CommitId;

/**
 * Exact branch-scoped comparison between two authoritative workflow commits.
 *
 * @param beforeCommit exact before commit
 * @param afterCommit exact after commit
 * @param beforeWorkflow authoritative before workflow
 * @param afterWorkflow authoritative after workflow
 * @param diff ordered semantic difference between both workflows
 */
public record WorkflowHistoryComparison(
    CommitId beforeCommit,
    CommitId afterCommit,
    Workflow beforeWorkflow,
    Workflow afterWorkflow,
    WorkflowDiff diff) {

  public WorkflowHistoryComparison {
    Objects.requireNonNull(beforeCommit, "beforeCommit");
    Objects.requireNonNull(afterCommit, "afterCommit");
    Objects.requireNonNull(beforeWorkflow, "beforeWorkflow");
    Objects.requireNonNull(afterWorkflow, "afterWorkflow");
    Objects.requireNonNull(diff, "diff");
  }
}
