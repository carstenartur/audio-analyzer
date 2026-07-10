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

/** Unit tests for {@link MergeConflictReport}. */
class MergeConflictReportTest {

  private static final Node NODE_A =
      new Node("node.a", "audio-input", "Input", List.of(), List.of());
  private static final Node NODE_B = new Node("node.b", "gain", "Gain", List.of(), List.of());
  private static final Node NODE_B_WITH_GAIN =
      new Node("node.b", "gain", "Gain", List.of(), List.of(), new Metadata(Map.of("gain", "2.0")));
  private static final Node NODE_C =
      new Node("node.c", "audio-output", "Output", List.of(), List.of());
  private static final Edge EDGE_AB = new Edge("edge.ab", "node.a", "out", "node.b", "in");

  private static Workflow workflow(List<Node> nodes, List<Edge> edges) {
    return new Workflow("workflow.test", "Test Workflow", nodes, edges);
  }

  @Test
  void noConflict_whenDiffsAreOrthogonal() {
    // base: A + B, no edges
    Workflow base = workflow(List.of(NODE_A, NODE_B), List.of());
    // ours: adds C
    Workflow ours = workflow(List.of(NODE_A, NODE_B, NODE_C), List.of());
    // theirs: adds edge A->B
    Workflow theirs = workflow(List.of(NODE_A, NODE_B), List.of(EDGE_AB));

    WorkflowDiff oursDiff = WorkflowDiff.compute(base, ours);
    WorkflowDiff theirsDiff = WorkflowDiff.compute(base, theirs);
    MergeConflictReport report = MergeConflictReport.detect(oursDiff, theirsDiff);

    assertFalse(report.hasConflicts(), "orthogonal changes should not conflict");
  }

  @Test
  void deleteVsModify_detectedWhenOursDeletesAndTheirsModifies() {
    // base: A + B, no edges
    Workflow base = workflow(List.of(NODE_A, NODE_B), List.of());
    // ours: removes B
    Workflow ours = workflow(List.of(NODE_A), List.of());
    // theirs: updates parameter on B
    Workflow theirs = workflow(List.of(NODE_A, NODE_B_WITH_GAIN), List.of());

    WorkflowDiff oursDiff = WorkflowDiff.compute(base, ours);
    WorkflowDiff theirsDiff = WorkflowDiff.compute(base, theirs);
    MergeConflictReport report = MergeConflictReport.detect(oursDiff, theirsDiff);

    assertTrue(report.hasConflicts(), "delete-vs-modify should be reported as conflict");
    assertEquals(1, report.conflicts().size());
    MergeConflict conflict = report.conflicts().get(0);
    assertInstanceOf(MergeConflict.DeleteVsModify.class, conflict);
    assertEquals("node.b", ((MergeConflict.DeleteVsModify) conflict).nodeId());
  }

  @Test
  void deleteVsModify_detectedWhenTheirsDeletesAndOursModifies() {
    Workflow base = workflow(List.of(NODE_A, NODE_B), List.of());
    // ours: modifies B
    Workflow ours = workflow(List.of(NODE_A, NODE_B_WITH_GAIN), List.of());
    // theirs: removes B
    Workflow theirs = workflow(List.of(NODE_A), List.of());

    WorkflowDiff oursDiff = WorkflowDiff.compute(base, ours);
    WorkflowDiff theirsDiff = WorkflowDiff.compute(base, theirs);
    MergeConflictReport report = MergeConflictReport.detect(oursDiff, theirsDiff);

    assertTrue(report.hasConflicts(), "symmetric delete-vs-modify should also be reported");
    assertEquals(1, report.conflicts().size());
    assertInstanceOf(MergeConflict.DeleteVsModify.class, report.conflicts().get(0));
  }

  @Test
  void connectVsParameterChange_detectedWhenOursConnectsAndTheirsModifiesEndpoint() {
    // base: A + B, no edges
    Workflow base = workflow(List.of(NODE_A, NODE_B), List.of());
    // ours: adds edge A->B
    Workflow ours = workflow(List.of(NODE_A, NODE_B), List.of(EDGE_AB));
    // theirs: modifies a parameter on B (the target of the new edge)
    Workflow theirs = workflow(List.of(NODE_A, NODE_B_WITH_GAIN), List.of());

    WorkflowDiff oursDiff = WorkflowDiff.compute(base, ours);
    WorkflowDiff theirsDiff = WorkflowDiff.compute(base, theirs);
    MergeConflictReport report = MergeConflictReport.detect(oursDiff, theirsDiff);

    assertTrue(report.hasConflicts(), "connect-vs-parameter-change should be reported as conflict");
    assertEquals(1, report.conflicts().size());
    MergeConflict conflict = report.conflicts().get(0);
    assertInstanceOf(MergeConflict.ConnectVsParameterChange.class, conflict);
    MergeConflict.ConnectVsParameterChange ctc = (MergeConflict.ConnectVsParameterChange) conflict;
    assertEquals("edge.ab", ctc.edgeId());
    assertEquals("node.b", ctc.nodeId());
  }

  @Test
  void connectVsParameterChange_detectedSymmetrically() {
    Workflow base = workflow(List.of(NODE_A, NODE_B), List.of());
    // ours: modifies parameter on B
    Workflow ours = workflow(List.of(NODE_A, NODE_B_WITH_GAIN), List.of());
    // theirs: adds edge A->B
    Workflow theirs = workflow(List.of(NODE_A, NODE_B), List.of(EDGE_AB));

    WorkflowDiff oursDiff = WorkflowDiff.compute(base, ours);
    WorkflowDiff theirsDiff = WorkflowDiff.compute(base, theirs);
    MergeConflictReport report = MergeConflictReport.detect(oursDiff, theirsDiff);

    assertTrue(
        report.hasConflicts(), "symmetric connect-vs-parameter-change should also be reported");
    assertInstanceOf(MergeConflict.ConnectVsParameterChange.class, report.conflicts().get(0));
  }

  @Test
  void noConflict_whenBothDiffsAreEmpty() {
    Workflow w = workflow(List.of(NODE_A), List.of());
    WorkflowDiff empty = WorkflowDiff.compute(w, w);
    MergeConflictReport report = MergeConflictReport.detect(empty, empty);
    assertFalse(report.hasConflicts());
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
