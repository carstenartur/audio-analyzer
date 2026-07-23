package org.hammer.audio.workflow.editor;

import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.WorkflowOperationLog;
import org.hammer.audio.workflow.WorkflowValidator;
import org.hammer.audio.workflow.dsl.WorkflowDslParser;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitInfo;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;
import org.hammer.audio.workflow.store.WorkflowSnapshot;

/**
 * Application-service boundary for the single-user React Flow workbench MVP (issue #210).
 *
 * <p>This service is the single server-authoritative entry point for graph editing, validation,
 * save/reload/history and execution handoff. Browser adapters call this service through a thin HTTP
 * layer; they must not access DSL, JGit, storage internals or mutable UI state as canonical
 * workflow state.
 *
 * <p><b>Thread safety</b>: public state access is serialised on the service instance. Validation,
 * dirty-state checks and graph replacement therefore form one atomic editor transition.
 *
 * <p><b>Dependency rules</b>: this class must not depend on Swing, JGit, React, Yjs, Selenium,
 * Playwright, Testcontainers or any web framework. It is a pure Java application service.
 */
public final class WorkflowEditorService {

  private final WorkflowOperationLog operationLog;
  private final WorkflowValidator validator;
  private final VersionedWorkflowStore workflowStore;
  private final WorkflowDslSerializer serializer;
  private final WorkflowDslParser parser;
  private boolean dirty;

  /**
   * Creates a service backed by the given operation log and validator.
   *
   * @param operationLog operation log holding the current workflow state
   * @param validator structural workflow validator
   */
  public WorkflowEditorService(WorkflowOperationLog operationLog, WorkflowValidator validator) {
    this(operationLog, validator, null);
  }

  /**
   * Creates a service backed by the given operation log, validator and checkpoint store.
   *
   * @param operationLog operation log holding the current workflow state
   * @param validator structural workflow validator
   * @param workflowStore workflow checkpoint store; may be {@code null} for non-persistent usage
   */
  public WorkflowEditorService(
      WorkflowOperationLog operationLog,
      WorkflowValidator validator,
      VersionedWorkflowStore workflowStore) {
    this.operationLog = Objects.requireNonNull(operationLog, "operationLog");
    this.validator = Objects.requireNonNull(validator, "validator");
    this.workflowStore = workflowStore;
    this.serializer = new WorkflowDslSerializer();
    this.parser = new WorkflowDslParser();
  }

  /**
   * Applies a workflow operation if it produces a valid workflow, or rejects it otherwise.
   *
   * <p>The operation is first applied to a candidate copy of the current workflow (no side
   * effects). If the candidate passes validation the operation is recorded in the log and the
   * updated projection is returned. If validation fails the log is left unchanged and {@link
   * WorkflowOperationRejectedException} is thrown.
   *
   * @param operation operation to apply
   * @return updated projection reflecting the new workflow state
   * @throws WorkflowOperationRejectedException if the resulting workflow violates structural rules
   * @throws IllegalArgumentException if the operation references nodes or ports that do not exist
   */
  public synchronized WorkflowProjection applyOperation(WorkflowOperation operation) {
    Objects.requireNonNull(operation, "operation");
    Workflow candidate = operation.apply(operationLog.currentWorkflow());
    List<String> violations = validator.validate(candidate);
    if (!violations.isEmpty()) {
      throw new WorkflowOperationRejectedException(violations);
    }
    operationLog.apply(operation);
    dirty = true;
    return WorkflowProjection.fromWorkflow(operationLog.currentWorkflow());
  }

  /** Returns whether the editable workflow differs from its last clean load/checkpoint state. */
  public synchronized boolean isDirty() {
    return dirty;
  }

  /**
   * Returns the projection of the current workflow state without applying any operation.
   *
   * @return current projection
   */
  public synchronized WorkflowProjection currentProjection() {
    return WorkflowProjection.fromWorkflow(operationLog.currentWorkflow());
  }

  /**
   * Loads the given workflow into the editor as a clean canonical state.
   *
   * @param workflow workflow to load
   * @return projection of the loaded workflow
   * @throws WorkflowOperationRejectedException if the workflow is structurally invalid
   */
  public synchronized WorkflowProjection loadGraph(Workflow workflow) {
    return replaceGraph(workflow, false);
  }

  /**
   * Replaces the editable workflow with an already confirmed imported setup and marks it unsaved.
   *
   * @param workflow already previewed workflow to import
   * @return projection of the imported workflow
   * @throws WorkflowOperationRejectedException if the workflow is structurally invalid
   */
  public synchronized WorkflowProjection importGraph(Workflow workflow) {
    return replaceGraph(workflow, true);
  }

  /**
   * Atomically imports a workflow, rejecting unsaved current state unless discard was confirmed.
   *
   * @param workflow already previewed workflow to import
   * @param discardDirty whether the caller explicitly confirmed discarding unsaved state
   * @return projection of the imported workflow
   * @throws DirtyWorkflowException when current state is dirty and discard was not confirmed
   * @throws WorkflowOperationRejectedException if the workflow is structurally invalid
   */
  public synchronized WorkflowProjection importGraph(Workflow workflow, boolean discardDirty) {
    if (dirty && !discardDirty) {
      throw new DirtyWorkflowException();
    }
    return replaceGraph(workflow, true);
  }

  /**
   * Loads the graph from the current HEAD of a branch in the configured store.
   *
   * @param branch branch to load
   * @return projection of the loaded workflow
   * @throws IllegalArgumentException if branch is blank or snapshot and DSL workflow IDs diverge
   */
  public synchronized WorkflowProjection loadGraph(String branch) {
    Objects.requireNonNull(branch, "branch");
    if (branch.isBlank()) {
      throw new IllegalArgumentException("branch must not be blank");
    }
    VersionedWorkflowStore store = requireStore();
    WorkflowSnapshot snapshot = store.loadHead(branch);
    Workflow workflow = parser.parse(snapshot.dslText());
    assertSnapshotIdMatchesDsl(snapshot.workflowId(), workflow.id());
    return replaceGraph(workflow, false);
  }

  /**
   * Loads the graph at a specific commit in the configured store.
   *
   * @param commitId commit identifier to load
   * @return projection of the loaded workflow
   * @throws IllegalArgumentException if snapshot and DSL workflow IDs diverge
   */
  public synchronized WorkflowProjection loadGraph(CommitId commitId) {
    Objects.requireNonNull(commitId, "commitId");
    VersionedWorkflowStore store = requireStore();
    WorkflowSnapshot snapshot = store.loadAtCommit(commitId);
    Workflow workflow = parser.parse(snapshot.dslText());
    assertSnapshotIdMatchesDsl(snapshot.workflowId(), workflow.id());
    return replaceGraph(workflow, false);
  }

  /**
   * Validates the current graph state.
   *
   * @return list of structural validation violations; empty when valid
   */
  public synchronized List<String> validate() {
    return validator.validate(operationLog.currentWorkflow());
  }

  /**
   * Persists a checkpoint of the current graph state in the configured store.
   *
   * <p>The current workflow is validated before persisting; if it is structurally invalid the
   * checkpoint is rejected and no commit is created.
   *
   * @param branch branch to commit to
   * @param metadata commit metadata (author/message/timestamp)
   * @return commit id of the created checkpoint
   * @throws IllegalArgumentException if branch is blank
   * @throws WorkflowOperationRejectedException if the current workflow is structurally invalid
   */
  public synchronized CommitId checkpoint(String branch, CommitMetadata metadata) {
    Objects.requireNonNull(branch, "branch");
    if (branch.isBlank()) {
      throw new IllegalArgumentException("branch must not be blank");
    }
    Objects.requireNonNull(metadata, "metadata");
    Workflow workflow = operationLog.currentWorkflow();
    List<String> violations = validator.validate(workflow);
    if (!violations.isEmpty()) {
      throw new WorkflowOperationRejectedException(violations);
    }
    VersionedWorkflowStore store = requireStore();
    WorkflowSnapshot snapshot = new WorkflowSnapshot(workflow.id(), serializer.serialize(workflow));
    CommitId commitId = store.commit(branch, snapshot, metadata);
    dirty = false;
    return commitId;
  }

  /**
   * Lists recent checkpoint commits for a branch/reference.
   *
   * @param refName branch or ref
   * @param limit max entries to return; must be &ge; 0
   * @return reverse-chronological commit summaries
   * @throws IllegalArgumentException if refName is blank or limit is negative
   */
  public synchronized List<CommitInfo> history(String refName, int limit) {
    Objects.requireNonNull(refName, "refName");
    if (refName.isBlank()) {
      throw new IllegalArgumentException("refName must not be blank");
    }
    if (limit < 0) {
      throw new IllegalArgumentException("limit must be >= 0");
    }
    VersionedWorkflowStore store = requireStore();
    return store.history(refName, limit);
  }

  /**
   * Produces a deterministic DSL snapshot of the current graph state for execution handoff.
   *
   * <p>This is not yet a full execution plan. It is the stable workflow snapshot consumed by the
   * future execution-integration layer from issue #211.
   *
   * @return immutable workflow snapshot
   */
  public synchronized WorkflowSnapshot executeSnapshot() {
    Workflow workflow = operationLog.currentWorkflow();
    return new WorkflowSnapshot(workflow.id(), serializer.serialize(workflow));
  }

  private WorkflowProjection replaceGraph(Workflow workflow, boolean imported) {
    Objects.requireNonNull(workflow, "workflow");
    List<String> violations = validator.validate(workflow);
    if (!violations.isEmpty()) {
      throw new WorkflowOperationRejectedException(violations);
    }
    operationLog.reset(workflow);
    dirty = imported;
    return WorkflowProjection.fromWorkflow(workflow);
  }

  private VersionedWorkflowStore requireStore() {
    if (workflowStore == null) {
      throw new IllegalStateException("workflowStore is not configured");
    }
    return workflowStore;
  }

  private static void assertSnapshotIdMatchesDsl(String snapshotId, String dslId) {
    if (!snapshotId.equals(dslId)) {
      throw new IllegalArgumentException(
          "snapshot workflowId '"
              + snapshotId
              + "' does not match DSL workflow id '"
              + dslId
              + "'");
    }
  }
}
