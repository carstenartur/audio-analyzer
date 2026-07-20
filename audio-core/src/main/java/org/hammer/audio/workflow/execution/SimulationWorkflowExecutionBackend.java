package org.hammer.audio.workflow.execution;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Control;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Input;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Mode;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Result;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Violation;

/** Dry-run backend that exercises lifecycle and plan ordering without audio computation. */
public final class SimulationWorkflowExecutionBackend
    implements WorkflowRunModels.ExecutionBackend {

  private final Clock clock;

  /** Creates a simulation backend using UTC wall-clock time. */
  public SimulationWorkflowExecutionBackend() {
    this(Clock.systemUTC());
  }

  SimulationWorkflowExecutionBackend(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Mode mode() {
    return Mode.SIMULATION;
  }

  @Override
  public List<Violation> validate(Input input) {
    Objects.requireNonNull(input, "input");
    return List.of();
  }

  @Override
  public Result execute(Input input, Control control) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(control, "control");
    Instant startedAt = clock.instant();
    ExecutionContext context =
        new ExecutionContext(input.runId() + ":execution", input.plan(), startedAt);
    List<String> nodeIds = input.plan().orderedNodeIds();
    for (int index = 0; index < nodeIds.size(); index++) {
      String nodeId = nodeIds.get(index);
      if (control.cancellationRequested()) {
        cancelRemaining(context, nodeIds, index);
        break;
      }
      context.updateNodeStatus(nodeId, ExecutionStatus.RUNNING);
      context.updateNodeStatus(nodeId, ExecutionStatus.COMPLETED);
      int progress = nodeIds.isEmpty() ? 100 : Math.multiplyExact(index + 1, 100) / nodeIds.size();
      control.progress(progress, "Simulated " + nodeId);
    }
    if (nodeIds.isEmpty()) {
      control.progress(100, "Simulation completed");
    }
    ExecutionResult executionResult = context.toResult(clock.instant());
    ReproducibilityBundle bundle =
        new ReproducibilityBundle(input.snapshot(), executionResult, input.commitId(), null);
    return new Result(bundle, Map.of("backendMode", Mode.SIMULATION.name()));
  }

  private static void cancelRemaining(
      ExecutionContext context, List<String> nodeIds, int firstRemainingIndex) {
    for (int index = firstRemainingIndex; index < nodeIds.size(); index++) {
      context.updateNodeStatus(nodeIds.get(index), ExecutionStatus.CANCELLED);
    }
  }
}
