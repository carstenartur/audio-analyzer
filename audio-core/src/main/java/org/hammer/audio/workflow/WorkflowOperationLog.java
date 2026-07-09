package org.hammer.audio.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Operation log that applies semantic workflow operations, supports replay and undo. */
public final class WorkflowOperationLog {

  private Workflow initialWorkflow;
  private Workflow current;
  private final List<WorkflowOperation> operationHistory;

  public WorkflowOperationLog(Workflow initialWorkflow) {
    this.initialWorkflow = Objects.requireNonNull(initialWorkflow, "initialWorkflow");
    this.current = initialWorkflow;
    this.operationHistory = new ArrayList<>();
  }

  public Workflow currentWorkflow() {
    return current;
  }

  public List<WorkflowOperation> operations() {
    return List.copyOf(operationHistory);
  }

  public Workflow apply(WorkflowOperation operation) {
    Objects.requireNonNull(operation, "operation");
    current = operation.apply(current);
    operationHistory.add(operation);
    return current;
  }

  public Workflow replay() {
    Workflow replayedWorkflow = initialWorkflow;
    for (WorkflowOperation operation : operationHistory) {
      replayedWorkflow = operation.apply(replayedWorkflow);
    }
    return replayedWorkflow;
  }

  public Workflow undoLast() {
    if (operationHistory.isEmpty()) {
      throw new IllegalStateException("No operation available to undo");
    }
    WorkflowOperation last = operationHistory.remove(operationHistory.size() - 1);
    WorkflowOperation inverse =
        last.inverseOperation()
            .orElseThrow(
                () ->
                    new UnsupportedOperationException(
                        "Operation has no inverse: " + last.getClass().getSimpleName()));
    current = inverse.apply(current);
    return current;
  }

  /**
   * Replaces the log state with a new workflow baseline and clears operation history.
   *
   * @param workflow new current and replay baseline workflow
   */
  public void reset(Workflow workflow) {
    this.initialWorkflow = Objects.requireNonNull(workflow, "workflow");
    this.current = workflow;
    this.operationHistory.clear();
  }
}
