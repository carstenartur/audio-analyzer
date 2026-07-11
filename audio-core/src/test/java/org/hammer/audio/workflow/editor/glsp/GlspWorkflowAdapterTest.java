package org.hammer.audio.workflow.editor.glsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperationLog;
import org.hammer.audio.workflow.WorkflowValidator;
import org.hammer.audio.workflow.catalog.ExperimentNodeCatalog;
import org.hammer.audio.workflow.editor.WorkflowEditorService;
import org.hammer.audio.workflow.editor.WorkflowOperationRejectedException;
import org.junit.jupiter.api.Test;

class GlspWorkflowAdapterTest {

  private static final Instant TIME = Instant.parse("2026-07-11T12:00:00Z");

  @Test
  void projectsTypedPortsAndAppliesValidEdgeThroughServerAuthority() {
    GlspWorkflowAdapter adapter = adapterWithSignalGenerator();

    GlspWorkflowAdapter.GGraph initial = adapter.currentGraph();
    assertEquals(2, initial.nodes().size());
    assertEquals("AudioBlock", initial.nodes().get(0).outputPorts().get(0).dataType());

    GlspWorkflowAdapter.GGraph updated =
        adapter.apply(
            new GlspWorkflowAdapter.CreateEdgeAction(
                "op.glsp.connect",
                TIME,
                "glsp-spike",
                "edge.generator-gain",
                "node.generator",
                "signal-out",
                "node.gain",
                "audio-in"));

    assertEquals(1, updated.edges().size());
    assertEquals(
        "node.generator::signal-out", updated.edges().get(0).sourcePortId());
    assertEquals("node.gain::audio-in", updated.edges().get(0).targetPortId());
  }

  @Test
  void rejectsInvalidDatasetToAudioBlockEdgeWithoutUpdatingGraph() {
    Workflow workflow =
        new Workflow(
            "workflow.glsp.invalid",
            "GLSP invalid edge",
            List.of(
                ExperimentNodeCatalog.recordingInput("node.recording"),
                ExperimentNodeCatalog.gain("node.gain")),
            List.of());
    GlspWorkflowAdapter adapter = adapter(workflow);

    assertThrows(
        WorkflowOperationRejectedException.class,
        () ->
            adapter.apply(
                new GlspWorkflowAdapter.CreateEdgeAction(
                    "op.glsp.invalid",
                    TIME,
                    "glsp-spike",
                    "edge.invalid",
                    "node.recording",
                    "audio-out",
                    "node.gain",
                    "audio-in")));
    assertEquals(0, adapter.currentGraph().edges().size());
  }

  @Test
  void translatesPropertyEditAndEdgeRemoval() {
    GlspWorkflowAdapter adapter = adapterWithSignalGenerator();
    adapter.apply(
        new GlspWorkflowAdapter.CreateEdgeAction(
            "op.glsp.connect",
            TIME,
            "glsp-spike",
            "edge.generator-gain",
            "node.generator",
            "signal-out",
            "node.gain",
            "audio-in"));

    GlspWorkflowAdapter.GGraph withProperty =
        adapter.apply(
            new GlspWorkflowAdapter.ChangePropertyAction(
                "op.glsp.property", TIME, "glsp-spike", "node.gain", "gain", "2.5"));
    assertEquals("2.5", withProperty.nodes().get(1).properties().get("gain"));

    GlspWorkflowAdapter.GGraph disconnected =
        adapter.apply(
            new GlspWorkflowAdapter.DeleteEdgeAction(
                "op.glsp.disconnect", TIME, "glsp-spike", "edge.generator-gain"));
    assertEquals(0, disconnected.edges().size());
  }

  private static GlspWorkflowAdapter adapterWithSignalGenerator() {
    Workflow workflow =
        new Workflow(
            "workflow.glsp",
            "GLSP Input Gain",
            List.of(
                ExperimentNodeCatalog.syntheticSignalGenerator("node.generator"),
                ExperimentNodeCatalog.gain("node.gain")),
            List.of());
    return adapter(workflow);
  }

  private static GlspWorkflowAdapter adapter(Workflow workflow) {
    WorkflowEditorService service =
        new WorkflowEditorService(
            new WorkflowOperationLog(workflow), new WorkflowValidator());
    return new GlspWorkflowAdapter(service);
  }
}
