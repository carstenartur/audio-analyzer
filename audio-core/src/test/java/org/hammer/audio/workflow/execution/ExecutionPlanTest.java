package org.hammer.audio.workflow.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Port;
import org.hammer.audio.workflow.PortDirection;
import org.hammer.audio.workflow.PortMultiplicity;
import org.hammer.audio.workflow.Workflow;
import org.junit.jupiter.api.Test;

class ExecutionPlanTest {

  private static final Instant NOW = Instant.parse("2024-01-01T00:00:00Z");

  private static Node sourceNode() {
    return new Node(
        "node.source",
        "source",
        "Source",
        List.of(),
        List.of(
            new Port(
                "out", "output", PortDirection.OUTPUT, "dataset", true, PortMultiplicity.SINGLE)));
  }

  private static Node transformNode() {
    return new Node(
        "node.transform",
        "transform",
        "Transform",
        List.of(
            new Port("in", "input", PortDirection.INPUT, "dataset", true, PortMultiplicity.SINGLE)),
        List.of(
            new Port(
                "out", "output", PortDirection.OUTPUT, "dataset", true, PortMultiplicity.SINGLE)));
  }

  private static Node sinkNode() {
    return new Node(
        "node.sink",
        "sink",
        "Sink",
        List.of(
            new Port("in", "input", PortDirection.INPUT, "dataset", true, PortMultiplicity.SINGLE)),
        List.of());
  }

  @Test
  void ordersNodesTopologically() {
    Workflow workflow =
        new Workflow(
            "workflow.linear",
            "Linear Workflow",
            List.of(sinkNode(), transformNode(), sourceNode()),
            List.of(
                new Edge("edge.1", "node.source", "out", "node.transform", "in"),
                new Edge("edge.2", "node.transform", "out", "node.sink", "in")));

    ExecutionSnapshot snapshot = ExecutionSnapshot.of("snap.1", workflow, NOW);
    ExecutionPlan plan = ExecutionPlan.of("plan.1", snapshot);

    List<String> ordered = plan.orderedNodeIds();
    assertEquals(3, ordered.size());
    // source must come before transform
    assertTrue(ordered.indexOf("node.source") < ordered.indexOf("node.transform"));
    // transform must come before sink
    assertTrue(ordered.indexOf("node.transform") < ordered.indexOf("node.sink"));
  }

  @Test
  void handlesWorkflowWithNoEdges() {
    Workflow workflow =
        new Workflow(
            "workflow.isolated", "Isolated Nodes", List.of(sourceNode(), sinkNode()), List.of());

    ExecutionSnapshot snapshot = ExecutionSnapshot.of("snap.2", workflow, NOW);
    ExecutionPlan plan = ExecutionPlan.of("plan.2", snapshot);

    assertEquals(2, plan.orderedNodeIds().size());
    assertTrue(plan.orderedNodeIds().contains("node.source"));
    assertTrue(plan.orderedNodeIds().contains("node.sink"));
  }

  @Test
  void recordsSnapshotId() {
    Workflow workflow = new Workflow("workflow.id", "ID Test", List.of(sourceNode()), List.of());

    ExecutionSnapshot snapshot = ExecutionSnapshot.of("snap.ref", workflow, NOW);
    ExecutionPlan plan = ExecutionPlan.of("plan.ref", snapshot);

    assertEquals("snap.ref", plan.snapshotId());
    assertEquals("plan.ref", plan.planId());
  }

  @Test
  void detectsCycleInWorkflowGraph() {
    // Build two nodes that form a cycle via edges that bypass port validation
    Node nodeA =
        new Node(
            "node.a",
            "type-a",
            "A",
            List.of(
                new Port(
                    "in", "input", PortDirection.INPUT, "dataset", false, PortMultiplicity.SINGLE)),
            List.of(
                new Port(
                    "out",
                    "output",
                    PortDirection.OUTPUT,
                    "dataset",
                    false,
                    PortMultiplicity.SINGLE)));
    Node nodeB =
        new Node(
            "node.b",
            "type-b",
            "B",
            List.of(
                new Port(
                    "in", "input", PortDirection.INPUT, "dataset", false, PortMultiplicity.SINGLE)),
            List.of(
                new Port(
                    "out",
                    "output",
                    PortDirection.OUTPUT,
                    "dataset",
                    false,
                    PortMultiplicity.SINGLE)));

    // Manually build a snapshot that represents a cyclic graph
    ExecutionSnapshot cyclicSnapshot =
        new ExecutionSnapshot(
            "snap.cycle",
            "workflow.cycle",
            List.of(nodeA, nodeB),
            List.of(
                new Edge("edge.a-to-b", "node.a", "out", "node.b", "in"),
                new Edge("edge.b-to-a", "node.b", "out", "node.a", "in")),
            null,
            NOW);

    assertThrows(
        IllegalArgumentException.class, () -> ExecutionPlan.of("plan.cycle", cyclicSnapshot));
  }

  @Test
  void rejectsNullPlanId() {
    Workflow workflow = new Workflow("workflow.np", "NP", List.of(sourceNode()), List.of());
    ExecutionSnapshot snapshot = ExecutionSnapshot.of("snap.np", workflow, NOW);
    assertThrows(NullPointerException.class, () -> ExecutionPlan.of(null, snapshot));
  }

  @Test
  void rejectsNullSnapshot() {
    assertThrows(NullPointerException.class, () -> ExecutionPlan.of("plan.ns", null));
  }
}
