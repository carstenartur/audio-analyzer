package org.hammer.audio.workflow.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.catalog.ExperimentNodeCatalog;
import org.hammer.audio.workflow.merge.WorkflowDiffModels.Change;
import org.hammer.audio.workflow.merge.WorkflowDiffModels.ChangeKind;
import org.hammer.audio.workflow.merge.WorkflowDiffModels.Diff;
import org.hammer.audio.workflow.merge.WorkflowDiffModels.ElementKind;
import org.junit.jupiter.api.Test;

class WorkflowSemanticDifferTest {

  private final WorkflowSemanticDiffer differ = new WorkflowSemanticDiffer();

  @Test
  void identicalWorkflowsHaveNoSemanticChanges() {
    Workflow workflow = baseWorkflow();

    Diff diff = differ.diff(workflow, workflow);

    assertTrue(diff.empty());
    assertEquals(List.of(), diff.changes());
  }

  @Test
  void comparesFieldsByStableSemanticIdentityInDeterministicOrder() {
    Workflow before = baseWorkflow();
    Node generator = before.nodes().getFirst();
    Node gain = before.nodes().get(1);
    Node changedGain =
        new Node(
            gain.id(),
            gain.type(),
            "Output gain",
            gain.inputPorts(),
            gain.outputPorts(),
            new Metadata(Map.of("gain.factor", "0.75")));
    Edge changedEdge =
        new Edge(
            before.edges().getFirst().id(),
            generator.id(),
            "signal-out",
            gain.id(),
            "audio-in",
            new Metadata(Map.of("route", "primary")));
    Workflow after =
        new Workflow(
            before.id(),
            "Renamed workflow",
            List.of(generator, changedGain),
            List.of(changedEdge),
            new Metadata(Map.of("owner", "remote", "purpose", "benchmark")));

    Diff diff = differ.diff(before, after);

    assertEquals(
        List.of(
            change(
                ElementKind.WORKFLOW,
                before.id(),
                "metadata.owner",
                ChangeKind.MODIFIED,
                "local",
                "remote"),
            change(
                ElementKind.WORKFLOW,
                before.id(),
                "metadata.purpose",
                ChangeKind.ADDED,
                null,
                "benchmark"),
            change(
                ElementKind.WORKFLOW,
                before.id(),
                "name",
                ChangeKind.MODIFIED,
                "Base workflow",
                "Renamed workflow"),
            change(
                ElementKind.NODE,
                gain.id(),
                "label",
                ChangeKind.MODIFIED,
                gain.label(),
                "Output gain"),
            change(
                ElementKind.NODE,
                gain.id(),
                "metadata.gain.factor",
                ChangeKind.ADDED,
                null,
                "0.75"),
            change(
                ElementKind.EDGE,
                changedEdge.id(),
                "metadata.route",
                ChangeKind.ADDED,
                null,
                "primary")),
        diff.changes());
  }

  @Test
  void reportsWholeObjectAdditionAndRemovalWithoutDependingOnListPosition() {
    Workflow before = baseWorkflow();
    Node report = ExperimentNodeCatalog.report("node.report");
    Workflow after =
        new Workflow(
            before.id(),
            before.name(),
            List.of(before.nodes().get(1), report),
            List.of(),
            before.metadata());

    Diff diff = differ.diff(before, after);

    assertEquals(ChangeKind.REMOVED, changeFor(diff, ElementKind.NODE, "node.generator").changeKind());
    assertEquals(ChangeKind.ADDED, changeFor(diff, ElementKind.NODE, "node.report").changeKind());
    assertEquals(ChangeKind.REMOVED, changeFor(diff, ElementKind.EDGE, "edge.signal-gain").changeKind());
  }

  private static Change changeFor(Diff diff, ElementKind kind, String id) {
    return diff.changes().stream()
        .filter(change -> change.elementKind() == kind && change.elementId().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private static Change change(
      ElementKind elementKind,
      String elementId,
      String fieldPath,
      ChangeKind changeKind,
      String before,
      String after) {
    return new Change(elementKind, elementId, fieldPath, changeKind, before, after);
  }

  private static Workflow baseWorkflow() {
    Node generator = ExperimentNodeCatalog.syntheticSignalGenerator("node.generator");
    Node gain = ExperimentNodeCatalog.gain("node.gain");
    Edge edge =
        new Edge(
            "edge.signal-gain",
            generator.id(),
            "signal-out",
            gain.id(),
            "audio-in");
    return new Workflow(
        "workflow.merge",
        "Base workflow",
        List.of(generator, gain),
        List.of(edge),
        new Metadata(Map.of("owner", "local")));
  }
}
