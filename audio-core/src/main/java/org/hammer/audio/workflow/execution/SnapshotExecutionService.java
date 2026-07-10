package org.hammer.audio.workflow.execution;

import java.time.Instant;
import java.util.Objects;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.dsl.WorkflowDslParser;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitInfo;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;
import org.hammer.audio.workflow.store.WorkflowSnapshot;

/**
 * Application service for creating {@link ExecutionSnapshot} objects from stored workflow
 * checkpoints and driving a complete dry-run execution pipeline.
 *
 * <p>This service bridges the persistence layer ({@link VersionedWorkflowStore}) and the execution
 * model ({@link ExecutionSnapshot}, {@link ExecutionPlan}, {@link ExecutionContext}, {@link
 * ExecutionResult}). It is the entry point for issue #211: <em>Create ExecutionSnapshot from stored
 * experiment graph</em>.
 *
 * <p>The {@link #run} method performs a <em>dry-run</em> execution: all nodes are transitioned from
 * {@link ExecutionStatus#IDLE} through {@link ExecutionStatus#RUNNING} to {@link
 * ExecutionStatus#COMPLETED}. No actual node computation takes place. This approach satisfies the
 * acceptance criteria that execution starts from a stable stored snapshot and that running
 * execution is isolated from ongoing edits.
 *
 * <p><strong>Dependency rules</strong>: this class lives in the {@code workflow.execution} package
 * and may import from {@code org.hammer.audio.workflow} (including sub-packages) and Java SE only.
 * It must not depend on UI, Swing, JGit, Hibernate or recording packages.
 *
 * <p><strong>Thread safety</strong>: not thread-safe. Concurrent access must be serialised by the
 * caller.
 */
public final class SnapshotExecutionService {

  private final VersionedWorkflowStore store;
  private final WorkflowDslParser parser;

  /**
   * Creates a service backed by the given workflow store and DSL parser.
   *
   * @param store versioned workflow checkpoint store
   * @param parser DSL parser for deserialising stored workflows
   */
  public SnapshotExecutionService(VersionedWorkflowStore store, WorkflowDslParser parser) {
    this.store = Objects.requireNonNull(store, "store");
    this.parser = Objects.requireNonNull(parser, "parser");
  }

  /**
   * Creates an {@link ExecutionSnapshot} from the workflow stored at the given commit.
   *
   * <p>The stored DSL text is parsed back into the workflow domain model and then frozen into an
   * immutable snapshot. Subsequent edits to the stored workflow have no effect on the returned
   * snapshot.
   *
   * @param commitId identifier of the stored workflow checkpoint
   * @param snapshotId stable identifier for the resulting snapshot
   * @param createdAt instant at which the snapshot is taken
   * @return immutable snapshot of the workflow at the given commit
   * @throws java.util.NoSuchElementException if the commit does not exist in the store
   * @throws org.hammer.audio.workflow.dsl.WorkflowDslParseException if the stored DSL is malformed
   */
  public ExecutionSnapshot snapshotAtCommit(
      CommitId commitId, String snapshotId, Instant createdAt) {
    Objects.requireNonNull(commitId, "commitId");
    Objects.requireNonNull(snapshotId, "snapshotId");
    Objects.requireNonNull(createdAt, "createdAt");
    WorkflowSnapshot stored = store.loadAtCommit(commitId);
    Workflow workflow = parser.parse(stored.dslText());
    return ExecutionSnapshot.of(snapshotId, workflow, createdAt);
  }

  /**
   * Runs a dry-run execution of the given snapshot and returns a {@link ReproducibilityBundle}.
   *
   * <p>The method derives an {@link ExecutionPlan} from the snapshot (topological sort), creates an
   * {@link ExecutionContext} and transitions every node through {@link ExecutionStatus#RUNNING} to
   * {@link ExecutionStatus#COMPLETED}. The resulting {@link ExecutionResult} is bundled together
   * with the snapshot and the optional commit provenance into an immutable {@link
   * ReproducibilityBundle}.
   *
   * <p>The {@code commitId} and {@code commitInfo} parameters may be {@code null} when the snapshot
   * was created outside of version control (e.g. from the live editor state during testing).
   *
   * @param snapshot the workflow snapshot to execute
   * @param planId stable identifier for the execution plan
   * @param executionId stable identifier for this execution run
   * @param commitId version-control identifier of the stored checkpoint (may be {@code null})
   * @param commitInfo author, message and timestamp of the stored checkpoint (may be {@code null})
   * @param startedAt instant at which the execution started
   * @param completedAt instant at which the execution finished
   * @return reproducibility bundle capturing the snapshot, result and optional provenance
   * @throws IllegalArgumentException if the workflow graph contains a cycle
   */
  public ReproducibilityBundle run(
      ExecutionSnapshot snapshot,
      String planId,
      String executionId,
      CommitId commitId,
      CommitInfo commitInfo,
      Instant startedAt,
      Instant completedAt) {
    Objects.requireNonNull(snapshot, "snapshot");
    StableExecutionIds.requireStable(planId, "planId");
    StableExecutionIds.requireStable(executionId, "executionId");
    Objects.requireNonNull(startedAt, "startedAt");
    Objects.requireNonNull(completedAt, "completedAt");

    ExecutionPlan executionPlan = ExecutionPlan.of(planId, snapshot);
    ExecutionContext context = new ExecutionContext(executionId, executionPlan, startedAt);

    for (String nodeId : executionPlan.orderedNodeIds()) {
      context.updateNodeStatus(nodeId, ExecutionStatus.RUNNING);
      context.updateNodeStatus(nodeId, ExecutionStatus.COMPLETED);
    }

    ExecutionResult result = context.toResult(completedAt);
    return new ReproducibilityBundle(snapshot, result, commitId, commitInfo);
  }
}
