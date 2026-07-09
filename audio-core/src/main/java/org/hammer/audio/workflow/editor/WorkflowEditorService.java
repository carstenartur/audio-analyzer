package org.hammer.audio.workflow.editor;

import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.WorkflowOperationLog;
import org.hammer.audio.workflow.WorkflowValidator;

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

  private final WorkflowOperationLog operationLog;
  private final WorkflowValidator validator;

  /**
   * Creates a service backed by the given operation log and validator.
   *
   * @param operationLog operation log holding the current workflow state
   * @param validator structural workflow validator
   */
  public WorkflowEditorService(WorkflowOperationLog operationLog, WorkflowValidator validator) {
    this.operationLog = Objects.requireNonNull(operationLog, "operationLog");
    this.validator = Objects.requireNonNull(validator, "validator");
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
}
