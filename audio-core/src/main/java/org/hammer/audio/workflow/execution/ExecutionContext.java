package org.hammer.audio.workflow.execution;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Mutable runtime context for a single workflow execution.
 *
 * <p>Tracks per-node {@link ExecutionStatus} transitions from {@link ExecutionStatus#IDLE} through
 * to a terminal state. Once all nodes have reached a terminal state, call {@link
 * #toResult(Instant)} to obtain an immutable {@link ExecutionResult}.
 *
 * <p>This class is <em>not</em> thread-safe; external synchronization is required if instances are
 * accessed from multiple threads concurrently.
 */
public final class ExecutionContext {

  private static final Set<ExecutionStatus> TERMINAL_STATUSES =
      EnumSet.of(
          ExecutionStatus.COMPLETED,
          ExecutionStatus.FAILED,
          ExecutionStatus.SKIPPED,
          ExecutionStatus.CANCELLED);

  private final String executionId;
  private final ExecutionPlan plan;
  private final Instant startedAt;
  private final Map<String, ExecutionStatus> nodeStatuses;

  /**
   * Creates a new execution context for the given plan. All nodes start with status {@link
   * ExecutionStatus#IDLE}.
   *
   * @param executionId stable identifier for this execution
   * @param plan the execution plan to run
   * @param startedAt instant at which execution started
   */
  public ExecutionContext(String executionId, ExecutionPlan plan, Instant startedAt) {
    StableExecutionIds.requireStable(executionId, "executionId");
    this.executionId = executionId;
    this.plan = Objects.requireNonNull(plan, "plan");
    this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    this.nodeStatuses = new HashMap<>();
    for (String nodeId : plan.orderedNodeIds()) {
      nodeStatuses.put(nodeId, ExecutionStatus.IDLE);
    }
  }

  /** Returns the stable identifier for this execution. */
  public String executionId() {
    return executionId;
  }

  /** Returns the plan driving this execution. */
  public ExecutionPlan plan() {
    return plan;
  }

  /** Returns the instant at which this execution started. */
  public Instant startedAt() {
    return startedAt;
  }

  /**
   * Returns the current status of the node with the given identifier.
   *
   * @param nodeId node identifier
   * @return current status
   * @throws IllegalArgumentException if no node with that identifier exists in the plan
   */
  public ExecutionStatus nodeStatus(String nodeId) {
    ExecutionStatus status = nodeStatuses.get(nodeId);
    if (status == null) {
      throw new IllegalArgumentException("Unknown node: " + nodeId);
    }
    return status;
  }

  /**
   * Updates the status of a node in this execution context.
   *
   * @param nodeId node identifier
   * @param status new status
   * @throws IllegalArgumentException if no node with that identifier exists in the plan
   */
  public void updateNodeStatus(String nodeId, ExecutionStatus status) {
    Objects.requireNonNull(status, "status");
    if (!nodeStatuses.containsKey(nodeId)) {
      throw new IllegalArgumentException("Unknown node: " + nodeId);
    }
    nodeStatuses.put(nodeId, status);
  }

  /**
   * Returns {@code true} if every node has reached a terminal status ({@link
   * ExecutionStatus#COMPLETED}, {@link ExecutionStatus#FAILED}, {@link ExecutionStatus#SKIPPED} or
   * {@link ExecutionStatus#CANCELLED}).
   */
  public boolean isComplete() {
    return nodeStatuses.values().stream().allMatch(TERMINAL_STATUSES::contains);
  }

  /**
   * Produces an immutable {@link ExecutionResult} from the current node statuses.
   *
   * @param completedAt instant at which the execution finished
   * @return immutable result capturing the state of this context at the time of the call
   */
  public ExecutionResult toResult(Instant completedAt) {
    Objects.requireNonNull(completedAt, "completedAt");
    if (!isComplete()) {
      throw new IllegalStateException(
          "Execution is not complete and cannot be converted to result");
    }
    return new ExecutionResult(
        executionId, plan.planId(), Map.copyOf(nodeStatuses), startedAt, completedAt);
  }
}
