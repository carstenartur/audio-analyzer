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
 * Application service for the React Flow workflow editor spike (ADR-007).
 *
 * <p>This service is the single entry point for every user gesture arriving from the browser. It
 * implements the server-authoritative design described in {@code adr-007-editor-stack.md}:
 *
 * <ol>
 *   <li>Apply the {@link WorkflowOperation} to a candidate workflow (pure, no side effects).
 *   <li>Validate the candidate with {@link WorkflowValidator}.
 *   <li>If validation passes: record the operation in {@link WorkflowOperationLog} and return the
 *       updated {@link WorkflowProjection}.
 *   <li>If validation fails: throw {@link WorkflowOperationRejectedException}; the log is
 *       unchanged.
 * </ol>
 *
 * <p>The React Flow client must update its node/edge state only from the returned {@code
 * WorkflowProjection}. It must never treat its own in-memory state as the canonical workflow.
 *
 * <p><b>Thread safety</b>: not thread-safe. Concurrent access must be serialised by the caller
 * (e.g. via a request-scoped lock or a single-threaded actor).
 *
 * <p><b>Dependency rules</b>: this class must not depend on Swing, JGit, React, Yjs, or any web
 * framework. It is a pure Java application service.
 */
public final class WorkflowEditorService {

  private WorkflowOperationLog operationLog;
  private final WorkflowValidator validator;
  private final VersionedWorkflowStore workflowStore;
  private final WorkflowDslSerializer serializer;
  private final WorkflowDslParser parser;

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
  public WorkflowProjection applyOperation(WorkflowOperation operation) {
    Objects.requireNonNull(operation, "operation");
    Workflow candidate = operation.apply(operationLog.currentWorkflow());
    List<String> violations = validator.validate(candidate);
    if (!violations.isEmpty()) {
      throw new WorkflowOperationRejectedException(violations);
    }
    operationLog.apply(operation);
    return WorkflowProjection.fromWorkflow(operationLog.currentWorkflow());
  }

  /**
   * Returns the projection of the current workflow state without applying any operation.
   *
   * @return current projection
   */
  public WorkflowProjection currentProjection() {
    return WorkflowProjection.fromWorkflow(operationLog.currentWorkflow());
  }

  /**
   * Loads the given workflow into the editor as canonical state.
   *
   * @param workflow workflow to load
   * @return projection of the loaded workflow
   * @throws WorkflowOperationRejectedException if the workflow is structurally invalid
   */
  public WorkflowProjection loadGraph(Workflow workflow) {
    Objects.requireNonNull(workflow, "workflow");
    List<String> violations = validator.validate(workflow);
    if (!violations.isEmpty()) {
      throw new WorkflowOperationRejectedException(violations);
    }
    this.operationLog = new WorkflowOperationLog(workflow);
    return WorkflowProjection.fromWorkflow(workflow);
  }

  /**
   * Loads the graph from the current HEAD of a branch in the configured store.
   *
   * @param branch branch to load
   * @return projection of the loaded workflow
   */
  public WorkflowProjection loadGraph(String branch) {
    Objects.requireNonNull(branch, "branch");
    VersionedWorkflowStore store = requireStore();
    WorkflowSnapshot snapshot = store.loadHead(branch);
    return loadGraph(parser.parse(snapshot.dslText()));
  }

  /**
   * Loads the graph at a specific commit in the configured store.
   *
   * @param commitId commit identifier to load
   * @return projection of the loaded workflow
   */
  public WorkflowProjection loadGraph(CommitId commitId) {
    Objects.requireNonNull(commitId, "commitId");
    VersionedWorkflowStore store = requireStore();
    WorkflowSnapshot snapshot = store.loadAtCommit(commitId);
    return loadGraph(parser.parse(snapshot.dslText()));
  }

  /**
   * Validates the current graph state.
   *
   * @return list of structural validation violations; empty when valid
   */
  public List<String> validate() {
    return validator.validate(operationLog.currentWorkflow());
  }

  /**
   * Persists a checkpoint of the current graph state in the configured store.
   *
   * @param branch branch to commit to
   * @param metadata commit metadata (author/message/timestamp)
   * @return commit id of the created checkpoint
   */
  public CommitId checkpoint(String branch, CommitMetadata metadata) {
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(metadata, "metadata");
    VersionedWorkflowStore store = requireStore();
    Workflow workflow = operationLog.currentWorkflow();
    WorkflowSnapshot snapshot = new WorkflowSnapshot(workflow.id(), serializer.serialize(workflow));
    return store.commit(branch, snapshot, metadata);
  }

  /**
   * Lists recent checkpoint commits for a branch/reference.
   *
   * @param refName branch or ref
   * @param limit max entries to return
   * @return reverse-chronological commit summaries
   */
  public List<CommitInfo> history(String refName, int limit) {
    Objects.requireNonNull(refName, "refName");
    VersionedWorkflowStore store = requireStore();
    return store.history(refName, limit);
  }

  /**
   * Produces a deterministic DSL snapshot of the current graph state for execution workflows.
   *
   * @return immutable workflow snapshot
   */
  public WorkflowSnapshot executeSnapshot() {
    Workflow workflow = operationLog.currentWorkflow();
    return new WorkflowSnapshot(workflow.id(), serializer.serialize(workflow));
  }

  private VersionedWorkflowStore requireStore() {
    if (workflowStore == null) {
      throw new IllegalStateException("workflowStore is not configured");
    }
    return workflowStore;
  }
}
