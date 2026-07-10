package org.hammer.audio.workflow.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link WorkflowDiff}. */
class WorkflowDiffTest {

  private static final Node NODE_A =
      new Node("node.a", "audio-input", "Input", List.of(), List.of());
  private static final Node NODE_B = new Node("node.b", "gain", "Gain", List.of(), List.of());
  private static final Node NODE_C =
      new Node("node.c", "audio-output", "Output", List.of(), List.of());
  private static final Edge EDGE_AB = new Edge("edge.ab", "node.a", "out", "node.b", "in");

  private static Workflow workflow(List<Node> nodes, List<Edge> edges) {
    return new Workflow("workflow.test", "Test Workflow", nodes, edges);
  }

  @Test
  void emptyDiff_whenBothSnapshotsIdentical() {
    Workflow w = workflow(List.of(NODE_A, NODE_B), List.of());
    WorkflowDiff diff = WorkflowDiff.compute(w, w);
    assertTrue(diff.isEmpty(), "diff should be empty when snapshots are identical");
  }

  @Test
  void nodeAdded_detectedWhenPresentOnlyInAfter() {
    Workflow before = workflow(List.of(NODE_A), List.of());
    Workflow after = workflow(List.of(NODE_A, NODE_B), List.of());

    WorkflowDiff diff = WorkflowDiff.compute(before, after);

    assertFalse(diff.isEmpty());
    assertEquals(1, diff.changes().size());
    WorkflowChange change = diff.changes().get(0);
    assertInstanceOf(WorkflowChange.NodeAdded.class, change);
    assertEquals("node.b", ((WorkflowChange.NodeAdded) change).node().id());
  }

  @Test
  void nodeRemoved_detectedWhenPresentOnlyInBefore() {
    Workflow before = workflow(List.of(NODE_A, NODE_B), List.of());
    Workflow after = workflow(List.of(NODE_A), List.of());

    WorkflowDiff diff = WorkflowDiff.compute(before, after);

    assertFalse(diff.isEmpty());
    assertEquals(1, diff.changes().size());
    assertInstanceOf(WorkflowChange.NodeRemoved.class, diff.changes().get(0));
    assertEquals("node.b", ((WorkflowChange.NodeRemoved) diff.changes().get(0)).node().id());
  }

  @Test
  void edgeAdded_detectedWhenPresentOnlyInAfter() {
    Workflow before = workflow(List.of(NODE_A, NODE_B), List.of());
    Workflow after = workflow(List.of(NODE_A, NODE_B), List.of(EDGE_AB));

    WorkflowDiff diff = WorkflowDiff.compute(before, after);

    assertEquals(1, diff.changes().size());
    assertInstanceOf(WorkflowChange.EdgeAdded.class, diff.changes().get(0));
    assertEquals("edge.ab", ((WorkflowChange.EdgeAdded) diff.changes().get(0)).edge().id());
  }

  @Test
  void edgeRemoved_detectedWhenPresentOnlyInBefore() {
    Workflow before = workflow(List.of(NODE_A, NODE_B), List.of(EDGE_AB));
    Workflow after = workflow(List.of(NODE_A, NODE_B), List.of());

    WorkflowDiff diff = WorkflowDiff.compute(before, after);

    assertEquals(1, diff.changes().size());
    assertInstanceOf(WorkflowChange.EdgeRemoved.class, diff.changes().get(0));
    assertEquals("edge.ab", ((WorkflowChange.EdgeRemoved) diff.changes().get(0)).edge().id());
  }

  @Test
  void parameterChanged_detectedOnNode() {
    Node nodeWithGain =
        new Node(
            "node.b", "gain", "Gain", List.of(), List.of(), new Metadata(Map.of("gain", "1.0")));
    Node nodeWithGainUpdated =
        new Node(
            "node.b", "gain", "Gain", List.of(), List.of(), new Metadata(Map.of("gain", "2.5")));

    Workflow before = workflow(List.of(NODE_A, nodeWithGain), List.of());
    Workflow after = workflow(List.of(NODE_A, nodeWithGainUpdated), List.of());

    WorkflowDiff diff = WorkflowDiff.compute(before, after);

    assertEquals(1, diff.changes().size());
    assertInstanceOf(WorkflowChange.ParameterChanged.class, diff.changes().get(0));
    WorkflowChange.ParameterChanged pc = (WorkflowChange.ParameterChanged) diff.changes().get(0);
    assertEquals("node.b", pc.targetId());
    assertEquals("gain", pc.propertyKey());
    assertEquals("1.0", pc.oldValue());
    assertEquals("2.5", pc.newValue());
  }

  @Test
  void parameterAdded_reportedWithNullOldValue() {
    Node nodeWithoutProp = new Node("node.b", "gain", "Gain", List.of(), List.of());
    Node nodeWithProp =
        new Node(
            "node.b", "gain", "Gain", List.of(), List.of(), new Metadata(Map.of("gain", "0.8")));

    WorkflowDiff diff =
        WorkflowDiff.compute(
            workflow(List.of(NODE_A, nodeWithoutProp), List.of()),
            workflow(List.of(NODE_A, nodeWithProp), List.of()));

    assertEquals(1, diff.changes().size());
    WorkflowChange.ParameterChanged pc = (WorkflowChange.ParameterChanged) diff.changes().get(0);
    assertNull(pc.oldValue());
    assertEquals("0.8", pc.newValue());
  }

  @Test
  void parameterRemoved_reportedWithNullNewValue() {
    Node nodeWithProp =
        new Node(
            "node.b", "gain", "Gain", List.of(), List.of(), new Metadata(Map.of("gain", "0.8")));
    Node nodeWithoutProp = new Node("node.b", "gain", "Gain", List.of(), List.of());

    WorkflowDiff diff =
        WorkflowDiff.compute(
            workflow(List.of(NODE_A, nodeWithProp), List.of()),
            workflow(List.of(NODE_A, nodeWithoutProp), List.of()));

    assertEquals(1, diff.changes().size());
    WorkflowChange.ParameterChanged pc = (WorkflowChange.ParameterChanged) diff.changes().get(0);
    assertEquals("0.8", pc.oldValue());
    assertNull(pc.newValue());
  }

  @Test
  void multipleChanges_allReported() {
    Workflow before = workflow(List.of(NODE_A, NODE_B), List.of(EDGE_AB));
    Workflow after = workflow(List.of(NODE_A, NODE_C), List.of());

    WorkflowDiff diff = WorkflowDiff.compute(before, after);

    // edge.ab removed, node.b removed, node.c added
    long removedEdges =
        diff.changes().stream().filter(c -> c instanceof WorkflowChange.EdgeRemoved).count();
    long removedNodes =
        diff.changes().stream().filter(c -> c instanceof WorkflowChange.NodeRemoved).count();
    long addedNodes =
        diff.changes().stream().filter(c -> c instanceof WorkflowChange.NodeAdded).count();

    assertEquals(1, removedEdges, "one edge should be removed");
    assertEquals(1, removedNodes, "one node should be removed");
    assertEquals(1, addedNodes, "one node should be added");
  }

  @Test
  void parameterChanges_areReportedInDeterministicKeyOrder() {
    Node beforeNode =
        new Node(
            "node.b",
            "gain",
            "Gain",
            List.of(),
            List.of(),
            new Metadata(Map.of("zeta", "1.0", "alpha", "2.0")));
    Node afterNode =
        new Node(
            "node.b",
            "gain",
            "Gain",
            List.of(),
            List.of(),
            new Metadata(Map.of("zeta", "1.5", "alpha", "2.5", "middle", "3.0")));

    WorkflowDiff diff =
        WorkflowDiff.compute(
            workflow(List.of(NODE_A, beforeNode), List.of()),
            workflow(List.of(NODE_A, afterNode), List.of()));

    assertEquals(3, diff.changes().size());
    assertEquals("alpha", ((WorkflowChange.ParameterChanged) diff.changes().get(0)).propertyKey());
    assertEquals("middle", ((WorkflowChange.ParameterChanged) diff.changes().get(1)).propertyKey());
    assertEquals("zeta", ((WorkflowChange.ParameterChanged) diff.changes().get(2)).propertyKey());
  }

  // helper shim to avoid importing Assertions.assertNull/assertInstanceOf in a confusing way
  private static void assertNull(Object actual) {
    assertEquals(null, actual);
  }

  @SuppressWarnings("unchecked")
  private static <T> void assertInstanceOf(Class<T> expectedType, Object actual) {
    assertTrue(
        expectedType.isInstance(actual),
        "expected instance of "
            + expectedType.getSimpleName()
            + " but was "
            + (actual == null ? "null" : actual.getClass().getSimpleName()));
  }
}
