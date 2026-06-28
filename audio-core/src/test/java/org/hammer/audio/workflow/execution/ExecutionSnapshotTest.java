package org.hammer.audio.workflow.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Port;
import org.hammer.audio.workflow.PortDirection;
import org.hammer.audio.workflow.PortMultiplicity;
import org.hammer.audio.workflow.Workflow;
import org.junit.jupiter.api.Test;

class ExecutionSnapshotTest {

  private static final Instant NOW = Instant.parse("2024-01-01T00:00:00Z");

  private static Workflow buildWorkflow() {
    return new Workflow(
        "workflow.test",
        "Test Workflow",
        List.of(
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
                        PortMultiplicity.SINGLE))),
            new Node(
                "node.sink",
                "sink",
                "Sink",
                List.of(
                    new Port(
                        "in",
                        "input",
                        PortDirection.INPUT,
                        "dataset",
                        true,
                        PortMultiplicity.SINGLE)),
                List.of())),
        List.of(new Edge("edge.1", "node.source", "out", "node.sink", "in")),
        new Metadata(Map.of("owner", "test")));
  }

  @Test
  void capturesWorkflowContentsAtSnapshotTime() {
    Workflow workflow = buildWorkflow();

    ExecutionSnapshot snapshot = ExecutionSnapshot.of("snap.1", workflow, NOW);

    assertEquals("snap.1", snapshot.snapshotId());
    assertEquals("workflow.test", snapshot.workflowId());
    assertEquals(workflow.nodes(), snapshot.nodes());
    assertEquals(workflow.edges(), snapshot.edges());
    assertEquals(workflow.metadata(), snapshot.metadata());
    assertEquals(NOW, snapshot.createdAt());
  }

  @Test
  void snapshotNodeListIsUnmodifiable() {
    Workflow workflow = buildWorkflow();

    ExecutionSnapshot snapshot = ExecutionSnapshot.of("snap.2", workflow, NOW);

    // The snapshot's list must be unmodifiable (defensively copied)
    assertThrows(UnsupportedOperationException.class, () -> snapshot.nodes().clear());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.edges().clear());
  }

  @Test
  void twoSnapshotsFromSameWorkflowAreEquivalent() {
    Workflow workflow = buildWorkflow();

    ExecutionSnapshot first = ExecutionSnapshot.of("snap.a", workflow, NOW);
    ExecutionSnapshot second = ExecutionSnapshot.of("snap.a", workflow, NOW);

    assertEquals(first, second);
  }

  @Test
  void multipleSnapshotsCanBeCreatedFromSameWorkflow() {
    Workflow workflow = buildWorkflow();

    ExecutionSnapshot snap1 = ExecutionSnapshot.of("snap.x", workflow, NOW);
    ExecutionSnapshot snap2 = ExecutionSnapshot.of("snap.y", workflow, NOW.plusSeconds(60));

    assertEquals(workflow.nodes(), snap1.nodes());
    assertEquals(workflow.nodes(), snap2.nodes());
    assertEquals("snap.x", snap1.snapshotId());
    assertEquals("snap.y", snap2.snapshotId());
  }

  @Test
  void rejectsNullSnapshotId() {
    Workflow workflow = buildWorkflow();
    assertThrows(NullPointerException.class, () -> ExecutionSnapshot.of(null, workflow, NOW));
  }

  @Test
  void rejectsNullWorkflow() {
    assertThrows(NullPointerException.class, () -> ExecutionSnapshot.of("snap.1", null, NOW));
  }

  @Test
  void rejectsNullCreatedAt() {
    Workflow workflow = buildWorkflow();
    assertThrows(NullPointerException.class, () -> ExecutionSnapshot.of("snap.1", workflow, null));
  }

  @Test
  void rejectsBlankSnapshotId() {
    Workflow workflow = buildWorkflow();
    assertThrows(IllegalArgumentException.class, () -> ExecutionSnapshot.of("  ", workflow, NOW));
  }
}
