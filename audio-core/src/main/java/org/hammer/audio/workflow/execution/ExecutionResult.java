package org.hammer.audio.workflow.execution;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable record of a completed workflow execution.
 *
 * <p>Produced by {@link ExecutionContext#toResult(Instant)} once all nodes have reached a terminal
 * status. The result captures a frozen copy of the per-node statuses; the originating context may
 * be discarded after this point.
 *
 * @param executionId stable identifier for this execution
 * @param planId identifier of the plan that was executed
 * @param nodeStatuses terminal status of each node, keyed by node identifier
 * @param startedAt instant at which execution started
 * @param completedAt instant at which execution finished
 */
public record ExecutionResult(
    String executionId,
    String planId,
    Map<String, ExecutionStatus> nodeStatuses,
    Instant startedAt,
    Instant completedAt) {

  public ExecutionResult {
    Objects.requireNonNull(executionId, "executionId");
    if (executionId.isBlank()) {
      throw new IllegalArgumentException("executionId must not be blank");
    }
    Objects.requireNonNull(planId, "planId");
    if (planId.isBlank()) {
      throw new IllegalArgumentException("planId must not be blank");
    }
    Objects.requireNonNull(nodeStatuses, "nodeStatuses");
    Objects.requireNonNull(startedAt, "startedAt");
    Objects.requireNonNull(completedAt, "completedAt");
    nodeStatuses = Map.copyOf(nodeStatuses);
  }

  /**
   * Returns the overall status of this execution.
   *
   * <ul>
   *   <li>{@link ExecutionStatus#FAILED} — if any node failed.
   *   <li>{@link ExecutionStatus#CANCELLED} — if any node was cancelled (and none failed).
   *   <li>{@link ExecutionStatus#COMPLETED} — if every node completed successfully.
   *   <li>{@link ExecutionStatus#SKIPPED} — if no node failed or was cancelled but not all
   *       completed (e.g. some were skipped).
   * </ul>
   */
  public ExecutionStatus overallStatus() {
    if (nodeStatuses.containsValue(ExecutionStatus.FAILED)) {
      return ExecutionStatus.FAILED;
    }
    if (nodeStatuses.containsValue(ExecutionStatus.CANCELLED)) {
      return ExecutionStatus.CANCELLED;
    }
    if (nodeStatuses.values().stream().allMatch(s -> s == ExecutionStatus.COMPLETED)) {
      return ExecutionStatus.COMPLETED;
    }
    return ExecutionStatus.SKIPPED;
  }
}
