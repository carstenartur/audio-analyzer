package org.hammer.audio.workflow.history;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Port;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.catalog.ExperimentNodeCatalog;
import org.hammer.audio.workflow.history.WorkflowChange.ElementKind;
import org.junit.jupiter.api.Test;

class WorkflowDiffCompleteSemanticTest {

  @Test
  void reportsWorkflowNodePortAndEdgeFieldsThroughTheEstablishedDiffApi() {
    Node generator = ExperimentNodeCatalog.syntheticSignalGenerator("node.generator");
    Node gain = ExperimentNodeCatalog.gain("node.gain");
    Edge edge =
        new Edge("edge.route", generator.id(), "signal-out", gain.id(), "audio-in");
    Workflow before =
        new Workflow(
            "workflow.diff",
            "Before",
            List.of(generator, gain),
            List.of(edge),
            new Metadata(Map.of("owner", "local")));

    Port output = generator.outputPorts().getFirst();
    Port renamedOutput =
        new Port(
            "signal-out-v2",
            output.name(),
            output.direction(),
            output.dataType(),
            output.required(),
            output.multiplicity(),
            output.metadata());
    Node changedGenerator =
        new Node(
            generator.id(),
            generator.type(),
            "Renamed signal",
            generator.inputPorts(),
            List.of(renamedOutput),
            generator.metadata());
    Edge changedEdge =
        new Edge("edge.route", generator.id(), "signal-out-v2", gain.id(), "audio-in");
    Workflow after =
        new Workflow(
            before.id(),
            "After",
            List.of(changedGenerator, gain),
            List.of(changedEdge),
            new Metadata(Map.of("owner", "remote", "purpose", "merge-test")));

    WorkflowDiff diff = WorkflowDiff.compute(before, after);

    assertEquals(
        List.of(
            field(ElementKind.WORKFLOW, before.id(), "name", "Before", "After"),
            field(ElementKind.WORKFLOW, before.id(), "metadata.owner", "local", "remote"),
            field(
                ElementKind.WORKFLOW,
                before.id(),
                "metadata.purpose",
                null,
                "merge-test"),
            field(
                ElementKind.NODE,
                generator.id(),
                "label",
                "Synthetic Signal Generator",
                "Renamed signal"),
            diff.changes().get(4),
            diff.changes().get(5)),
        diff.changes());
    WorkflowChange.FieldChanged ports = (WorkflowChange.FieldChanged) diff.changes().get(4);
    assertEquals(ElementKind.NODE, ports.elementKind());
    assertEquals("outputPorts", ports.fieldPath());
    WorkflowChange.FieldChanged endpoints = (WorkflowChange.FieldChanged) diff.changes().get(5);
    assertEquals(ElementKind.EDGE, endpoints.elementKind());
    assertEquals("endpoints", endpoints.fieldPath());
  }

  private static WorkflowChange.FieldChanged field(
      ElementKind kind, String targetId, String path, String before, String after) {
    return new WorkflowChange.FieldChanged(kind, targetId, path, before, after);
  }
}
