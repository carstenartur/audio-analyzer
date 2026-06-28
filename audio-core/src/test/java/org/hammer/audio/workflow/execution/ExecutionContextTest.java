package org.hammer.audio.workflow.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Port;
import org.hammer.audio.workflow.PortDirection;
import org.hammer.audio.workflow.PortMultiplicity;
import org.hammer.audio.workflow.Workflow;
import org.junit.jupiter.api.Test;

class ExecutionContextTest {

  private static final Instant START = Instant.parse("2024-01-01T10:00:00Z");
  private static final Instant END = Instant.parse("2024-01-01T10:05:00Z");

  private static ExecutionPlan buildTwoNodePlan() {
    Node source =
        new Node(
            "node.source",
            "source",
            "Source",
            List.of(),
            List.of(
                new Port(
                    "out",
                    "output",
                    PortDirection.OUTPUT,
                    "dataset",
                    true,
                    PortMultiplicity.SINGLE)));
    Node sink =
        new Node(
            "node.sink",
            "sink",
            "Sink",
            List.of(
                new Port(
                    "in", "input", PortDirection.INPUT, "dataset", true, PortMultiplicity.SINGLE)),
            List.of());
    Workflow workflow =
        new Workflow("workflow.ctx", "Context Test", List.of(source, sink), List.of());
    ExecutionSnapshot snapshot = ExecutionSnapshot.of("snap.ctx", workflow, START);
    return ExecutionPlan.of("plan.ctx", snapshot);
  }

  @Test
  void allNodesStartAsIdle() {
    ExecutionPlan plan = buildTwoNodePlan();
    ExecutionContext context = new ExecutionContext("exec.1", plan, START);

    for (String nodeId : plan.orderedNodeIds()) {
      assertEquals(ExecutionStatus.IDLE, context.nodeStatus(nodeId));
    }
  }

  @Test
  void isNotCompleteWhenNodesAreIdle() {
    ExecutionPlan plan = buildTwoNodePlan();
    ExecutionContext context = new ExecutionContext("exec.2", plan, START);

    assertFalse(context.isComplete());
  }

  @Test
  void isCompleteWhenAllNodesReachTerminalStatus() {
    ExecutionPlan plan = buildTwoNodePlan();
    ExecutionContext context = new ExecutionContext("exec.3", plan, START);

    context.updateNodeStatus("node.source", ExecutionStatus.COMPLETED);
    context.updateNodeStatus("node.sink", ExecutionStatus.COMPLETED);

    assertTrue(context.isComplete());
  }

  @Test
  void isCompleteWithMixedTerminalStatuses() {
    ExecutionPlan plan = buildTwoNodePlan();
    ExecutionContext context = new ExecutionContext("exec.4", plan, START);

    context.updateNodeStatus("node.source", ExecutionStatus.COMPLETED);
    context.updateNodeStatus("node.sink", ExecutionStatus.SKIPPED);

    assertTrue(context.isComplete());
  }

  @Test
  void isNotCompleteWhenANodeIsStillRunning() {
    ExecutionPlan plan = buildTwoNodePlan();
    ExecutionContext context = new ExecutionContext("exec.5", plan, START);

    context.updateNodeStatus("node.source", ExecutionStatus.COMPLETED);
    context.updateNodeStatus("node.sink", ExecutionStatus.RUNNING);

    assertFalse(context.isComplete());
  }

  @Test
  void toResultProducesImmutableResult() {
    ExecutionPlan plan = buildTwoNodePlan();
    ExecutionContext context = new ExecutionContext("exec.6", plan, START);

    context.updateNodeStatus("node.source", ExecutionStatus.COMPLETED);
    context.updateNodeStatus("node.sink", ExecutionStatus.COMPLETED);

    ExecutionResult result = context.toResult(END);

    assertEquals("exec.6", result.executionId());
    assertEquals("plan.ctx", result.planId());
    assertEquals(ExecutionStatus.COMPLETED, result.nodeStatuses().get("node.source"));
    assertEquals(ExecutionStatus.COMPLETED, result.nodeStatuses().get("node.sink"));
    assertEquals(START, result.startedAt());
    assertEquals(END, result.completedAt());
  }

  @Test
  void toResultIsIsolatedFromSubsequentContextChanges() {
    ExecutionPlan plan = buildTwoNodePlan();
    ExecutionContext context = new ExecutionContext("exec.7", plan, START);

    context.updateNodeStatus("node.source", ExecutionStatus.COMPLETED);
    context.updateNodeStatus("node.sink", ExecutionStatus.COMPLETED);

    ExecutionResult result = context.toResult(END);

    // Mutate the context after taking the result; result must not be affected
    context.updateNodeStatus("node.source", ExecutionStatus.FAILED);

    assertEquals(ExecutionStatus.COMPLETED, result.nodeStatuses().get("node.source"));
  }

  @Test
  void toResultRejectsIncompleteExecution() {
    ExecutionPlan plan = buildTwoNodePlan();
    ExecutionContext context = new ExecutionContext("exec.incomplete", plan, START);

    context.updateNodeStatus("node.source", ExecutionStatus.COMPLETED);
    context.updateNodeStatus("node.sink", ExecutionStatus.RUNNING);

    assertThrows(IllegalStateException.class, () -> context.toResult(END));
  }

  @Test
  void rejectsUnknownNodeOnStatusRead() {
    ExecutionPlan plan = buildTwoNodePlan();
    ExecutionContext context = new ExecutionContext("exec.8", plan, START);

    assertThrows(IllegalArgumentException.class, () -> context.nodeStatus("node.unknown"));
  }

  @Test
  void rejectsUnknownNodeOnStatusUpdate() {
    ExecutionPlan plan = buildTwoNodePlan();
    ExecutionContext context = new ExecutionContext("exec.9", plan, START);

    assertThrows(
        IllegalArgumentException.class,
        () -> context.updateNodeStatus("node.unknown", ExecutionStatus.COMPLETED));
  }

  @Test
  void rejectsNullExecutionId() {
    ExecutionPlan plan = buildTwoNodePlan();
    assertThrows(IllegalArgumentException.class, () -> new ExecutionContext(null, plan, START));
  }

  @Test
  void rejectsInvalidExecutionIdFormat() {
    ExecutionPlan plan = buildTwoNodePlan();
    assertThrows(
        IllegalArgumentException.class, () -> new ExecutionContext("exec invalid", plan, START));
  }

  @Test
  void rejectsNullPlan() {
    assertThrows(NullPointerException.class, () -> new ExecutionContext("exec.np", null, START));
  }

  @Test
  void rejectsNullStartedAt() {
    ExecutionPlan plan = buildTwoNodePlan();
    assertThrows(NullPointerException.class, () -> new ExecutionContext("exec.ns", plan, null));
  }
}
