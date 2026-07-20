package org.hammer.audio.workflow.history;

import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.dsl.WorkflowDslParser;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitInfo;
import org.hammer.audio.workflow.store.StaleWorkflowHeadException;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;
import org.hammer.audio.workflow.store.WorkflowSnapshot;

/** Application service for explicit branch-scoped workflow comparison and non-destructive restore. */
public final class WorkflowHistoryCommandService {

  private final VersionedWorkflowStore store;
  private final WorkflowHistoryAccessPolicy accessPolicy;
  private final WorkflowDslParser parser;

  /** Creates a command service over one repository-scoped workflow store. */
  public WorkflowHistoryCommandService(
      VersionedWorkflowStore store, WorkflowHistoryAccessPolicy accessPolicy) {
    this.store = Objects.requireNonNull(store, "store");
    this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    this.parser = new WorkflowDslParser();
  }

  /** Compares two exact commits that are reachable from the requested branch. */
  public WorkflowHistoryComparison compare(
      String branch, CommitId beforeCommit, CommitId afterCommit) {
    String requiredBranch = requireNotBlank(branch, "branch");
    Objects.requireNonNull(beforeCommit, "beforeCommit");
    Objects.requireNonNull(afterCommit, "afterCommit");
    List<CommitInfo> reachable = store.history(requiredBranch, Integer.MAX_VALUE);
    requireReachable(reachable, beforeCommit, requiredBranch);
    requireReachable(reachable, afterCommit, requiredBranch);

    Workflow beforeWorkflow = loadWorkflow(beforeCommit);
    Workflow afterWorkflow = loadWorkflow(afterCommit);
    if (!beforeWorkflow.id().equals(afterWorkflow.id())) {
      throw new IllegalArgumentException(
          "Cannot compare commits with different workflow ids: "
              + beforeWorkflow.id()
              + " and "
              + afterWorkflow.id());
    }
    accessPolicy.assertCompareAllowed(requiredBranch, beforeWorkflow.id());
    return new WorkflowHistoryComparison(
        beforeCommit,
        afterCommit,
        beforeWorkflow,
        afterWorkflow,
        WorkflowDiff.compute(beforeWorkflow, afterWorkflow));
  }

  /** Restores a reachable historical snapshot as a new commit on the current branch HEAD. */
  public WorkflowRestoreResult restore(RestoreWorkflowVersionCommand command) {
    Objects.requireNonNull(command, "command");
    List<CommitInfo> reachable = store.history(command.branch(), Integer.MAX_VALUE);
    if (reachable.isEmpty()) {
      throw new IllegalArgumentException("Unknown or empty workflow branch: " + command.branch());
    }
    requireReachable(reachable, command.targetCommit(), command.branch());
    CommitId actualHead = reachable.getFirst().commitId();
    if (!command.expectedHead().equals(actualHead)) {
      throw new StaleWorkflowHeadException(command.branch(), command.expectedHead(), actualHead);
    }

    WorkflowSnapshot targetSnapshot = store.loadAtCommit(command.targetCommit());
    Workflow targetWorkflow = parseAndValidate(targetSnapshot);
    accessPolicy.assertRestoreAllowed(command.branch(), targetWorkflow.id());
    CommitId restoredCommit =
        store.commitIfHead(
            command.branch(), command.expectedHead(), targetSnapshot, command.metadata());
    return new WorkflowRestoreResult(
        command.branch(), command.targetCommit(), actualHead, restoredCommit);
  }

  private Workflow loadWorkflow(CommitId commitId) {
    return parseAndValidate(store.loadAtCommit(commitId));
  }

  private Workflow parseAndValidate(WorkflowSnapshot snapshot) {
    Workflow workflow = parser.parse(snapshot.dslText());
    if (!snapshot.workflowId().equals(workflow.id())) {
      throw new IllegalArgumentException(
          "snapshot workflowId '"
              + snapshot.workflowId()
              + "' does not match DSL workflow id '"
              + workflow.id()
              + "'");
    }
    return workflow;
  }

  private static void requireReachable(
      List<CommitInfo> reachable, CommitId commitId, String branch) {
    boolean found = reachable.stream().anyMatch(info -> info.commitId().equals(commitId));
    if (!found) {
      throw new IllegalArgumentException(
          "Commit " + commitId.value() + " is not reachable from branch " + branch);
    }
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    String normalized = value.trim();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return normalized;
  }
}
