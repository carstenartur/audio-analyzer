package org.hammer.audio.workflow.history;

import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.dsl.WorkflowDslParser;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.Resolution;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.Result;
import org.hammer.audio.workflow.merge.WorkflowThreeWayMerger;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitInfo;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.StaleWorkflowHeadException;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;
import org.hammer.audio.workflow.store.WorkflowSnapshot;

/** Application service for exact stored-commit semantic merge preview and checkpoint creation. */
public final class WorkflowMergeCommandService {

  private final VersionedWorkflowStore store;
  private final WorkflowHistoryAccessPolicy accessPolicy;
  private final WorkflowDslParser parser;
  private final WorkflowDslSerializer serializer;
  private final WorkflowThreeWayMerger merger;

  /** Creates the production merge service over one repository-scoped workflow store. */
  public WorkflowMergeCommandService(
      VersionedWorkflowStore store, WorkflowHistoryAccessPolicy accessPolicy) {
    this(
        store,
        accessPolicy,
        new WorkflowDslParser(),
        new WorkflowDslSerializer(),
        new WorkflowThreeWayMerger());
  }

  WorkflowMergeCommandService(
      VersionedWorkflowStore store,
      WorkflowHistoryAccessPolicy accessPolicy,
      WorkflowDslParser parser,
      WorkflowDslSerializer serializer,
      WorkflowThreeWayMerger merger) {
    this.store = Objects.requireNonNull(store, "store");
    this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    this.parser = Objects.requireNonNull(parser, "parser");
    this.serializer = Objects.requireNonNull(serializer, "serializer");
    this.merger = Objects.requireNonNull(merger, "merger");
  }

  /** Loads exact stored commits and returns a deterministic semantic merge preview. */
  public WorkflowMergePreview preview(PreviewWorkflowMergeCommand command) {
    MergeContext context = loadContext(command);
    accessPolicy.assertCompareAllowed(command.targetBranch(), context.base().id());
    accessPolicy.assertCompareAllowed(command.remoteBranch(), context.base().id());
    return new WorkflowMergePreview(
        command.targetBranch(),
        command.remoteBranch(),
        command.baseCommit(),
        command.localCommit(),
        command.remoteCommit(),
        context.base(),
        context.local(),
        context.remote(),
        merger.preview(context.base(), context.local(), context.remote()));
  }

  /** Resolves all conflicts, validates the candidate and commits it on the expected target HEAD. */
  public WorkflowMergeCommitResult resolveAndCommit(ResolveWorkflowMergeCommand command) {
    Objects.requireNonNull(command, "command");
    MergeContext context = loadContext(command.preview());
    if (!command.expectedHead().equals(context.actualTargetHead())) {
      throw new StaleWorkflowHeadException(
          command.preview().targetBranch(), command.expectedHead(), context.actualTargetHead());
    }

    List<Resolution> resolutions =
        command.resolutions().stream()
            .map(
                decision ->
                    new Resolution(
                        decision.conflictId(), decision.choice(), decision.customValue()))
            .toList();
    Result merged = merger.resolve(context.base(), context.local(), context.remote(), resolutions);
    if (!merged.readyToCommit()) {
      throw new WorkflowMergeRejectedException(
          merged.unresolvedConflicts(), merged.validationViolations());
    }

    accessPolicy.assertMergeAllowed(command.preview().targetBranch(), merged.workflow().id());
    CommitMetadata auditMetadata =
        WorkflowMergeAuditTrail.metadata(
            command.preview(), command.resolutions(), command.metadata());
    WorkflowSnapshot snapshot =
        new WorkflowSnapshot(merged.workflow().id(), serializer.serialize(merged.workflow()));
    CommitId mergedCommit =
        store.commitIfHead(
            command.preview().targetBranch(), command.expectedHead(), snapshot, auditMetadata);
    Workflow reloaded = parseAndValidate(store.loadAtCommit(mergedCommit));
    if (!merged.workflow().equals(reloaded)) {
      throw new IllegalStateException(
          "Reloaded merge checkpoint differs from the deterministic resolved workflow");
    }
    return new WorkflowMergeCommitResult(
        command.preview().targetBranch(),
        command.preview().baseCommit(),
        command.preview().localCommit(),
        command.preview().remoteCommit(),
        mergedCommit,
        reloaded,
        auditMetadata.message());
  }

  private MergeContext loadContext(PreviewWorkflowMergeCommand command) {
    Objects.requireNonNull(command, "command");
    List<CommitInfo> targetHistory = store.history(command.targetBranch(), Integer.MAX_VALUE);
    List<CommitInfo> remoteHistory = store.history(command.remoteBranch(), Integer.MAX_VALUE);
    if (targetHistory.isEmpty()) {
      throw new IllegalArgumentException(
          "Unknown or empty target workflow branch: " + command.targetBranch());
    }
    if (remoteHistory.isEmpty()) {
      throw new IllegalArgumentException(
          "Unknown or empty remote workflow branch: " + command.remoteBranch());
    }
    requireReachable(targetHistory, command.baseCommit(), command.targetBranch());
    requireReachable(targetHistory, command.localCommit(), command.targetBranch());
    requireReachable(remoteHistory, command.baseCommit(), command.remoteBranch());
    requireReachable(remoteHistory, command.remoteCommit(), command.remoteBranch());

    Workflow base = loadWorkflow(command.baseCommit());
    Workflow local = loadWorkflow(command.localCommit());
    Workflow remote = loadWorkflow(command.remoteCommit());
    requireSameWorkflow(base, local, remote);
    return new MergeContext(base, local, remote, targetHistory.getFirst().commitId());
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

  private static void requireSameWorkflow(Workflow base, Workflow local, Workflow remote) {
    if (!base.id().equals(local.id()) || !base.id().equals(remote.id())) {
      throw new IllegalArgumentException(
          "Cannot merge commits with different workflow ids: "
              + base.id()
              + ", "
              + local.id()
              + " and "
              + remote.id());
    }
  }

  private static void requireReachable(
      List<CommitInfo> history, CommitId commitId, String branch) {
    boolean found = history.stream().anyMatch(info -> info.commitId().equals(commitId));
    if (!found) {
      throw new IllegalArgumentException(
          "Commit " + commitId.value() + " is not reachable from branch " + branch);
    }
  }

  private record MergeContext(
      Workflow base, Workflow local, Workflow remote, CommitId actualTargetHead) {
    MergeContext {
      Objects.requireNonNull(base, "base");
      Objects.requireNonNull(local, "local");
      Objects.requireNonNull(remote, "remote");
      Objects.requireNonNull(actualTargetHead, "actualTargetHead");
    }
  }
}
