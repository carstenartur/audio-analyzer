package org.hammer.audio.workflow.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.hammer.audio.workflow.DataTypes;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Port;
import org.hammer.audio.workflow.PortDirection;
import org.hammer.audio.workflow.PortMultiplicity;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.WorkflowOperationLog;
import org.hammer.audio.workflow.WorkflowValidator;
import org.hammer.audio.workflow.catalog.ExperimentNodeCatalog;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.InMemoryVersionedWorkflowStore;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;
import org.hammer.audio.workflow.store.WorkflowSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Executable tests for {@link WorkflowEditorService} (ADR-007 / issue #210 workbench boundary).
 */
class WorkflowEditorServiceTest {

  private static final Instant OP_TIME = Instant.parse("2026-01-01T12:00:00Z");
  private static final String AUTHOR = "spike-test";
  private static final String INPUT_ID = "node.input";
  private static final String GAIN_ID = "node.gain";
  private static final String OUTPUT_ID = "node.output";
  private static final String RECORDING_INPUT_ID = "node.recording";
  private static final String SIGNAL_OUT = "signal-out";
  private static final String AUDIO_IN = "audio-in";

  private WorkflowEditorService service;
  private WorkflowOperationLog log;

  @BeforeEach
  void setUp() {
    Workflow initial = initialWorkflow();
    log = new WorkflowOperationLog(initial);
    service = new WorkflowEditorService(log, new WorkflowValidator());
  }

  @Test
  void validEdge_signalGeneratorToGain_acceptedAndProjected() {
    Edge edge = new Edge("edge.input-to-gain", INPUT_ID, SIGNAL_OUT, GAIN_ID, AUDIO_IN);
    WorkflowOperation connectOp =
        new WorkflowOperation.ConnectPorts("op.connect.valid", OP_TIME, AUTHOR, edge);

    WorkflowProjection projection = service.applyOperation(connectOp);

    assertEquals(1, projection.edges().size(), "one edge should be present in projection");
    WorkflowProjection.EdgeProjection ep = projection.edges().get(0);
    assertEquals("edge.input-to-gain", ep.id());
    assertEquals(INPUT_ID, ep.source());
    assertEquals(SIGNAL_OUT, ep.sourceHandle());
    assertEquals(GAIN_ID, ep.target());
    assertEquals(AUDIO_IN, ep.targetHandle());
    assertEquals(1, log.operations().size(), "operation should be recorded in the log");
  }

  @Test
  void invalidEdge_datasetToAudioBlock_rejectedAndLogUnchanged() {
    Node recordingInput = ExperimentNodeCatalog.recordingInput(RECORDING_INPUT_ID);
    Workflow withRecording =
        new Workflow(
            "workflow.spike2",
            "Invalid Edge Spike",
            List.of(recordingInput, ExperimentNodeCatalog.gain(GAIN_ID), audioOutputNode(OUTPUT_ID)),
            List.of());
    WorkflowOperationLog rejectionLog = new WorkflowOperationLog(withRecording);
    WorkflowEditorService rejectionService =
        new WorkflowEditorService(rejectionLog, new WorkflowValidator());
    Edge mismatchedEdge =
        new Edge("edge.mismatch", RECORDING_INPUT_ID, "audio-out", GAIN_ID, AUDIO_IN);
    WorkflowOperation connectOp =
        new WorkflowOperation.ConnectPorts("op.connect.invalid", OP_TIME, AUTHOR, mismatchedEdge);

    WorkflowOperationRejectedException ex =
        assertThrows(
            WorkflowOperationRejectedException.class,
            () -> rejectionService.applyOperation(connectOp));

    assertFalse(ex.violations().isEmpty(), "rejection must carry at least one violation message");
    assertTrue(
        ex.violations().stream().anyMatch(v -> v.contains("incompatible data types")),
        "violation should describe incompatible data types; got: " + ex.violations());
    assertEquals(0, rejectionLog.operations().size(), "log must be unchanged after rejection");
  }

  @Test
  void parameterUpdate_gainNode_appliedAndVisibleInProjection() {
    WorkflowProjection projection = service.applyOperation(updateGain("op.update.gain", "1.5"));

    assertEquals(1, log.operations().size(), "update operation should be recorded in the log");
    WorkflowProjection.NodeProjection gainProjection = findNode(projection, GAIN_ID);
    assertEquals("1.5", gainProjection.properties().get("gain"));
  }

  @Test
  void disconnectPorts_existingEdge_removedAndNotInProjection() {
    Edge edge = new Edge("edge.input-to-gain", INPUT_ID, SIGNAL_OUT, GAIN_ID, AUDIO_IN);
    service.applyOperation(new WorkflowOperation.ConnectPorts("op.connect", OP_TIME, AUTHOR, edge));

    WorkflowProjection projection =
        service.applyOperation(
            new WorkflowOperation.DisconnectPorts("op.disconnect", OP_TIME, AUTHOR, edge.id(), edge));

    assertEquals(0, projection.edges().size(), "projection must not contain disconnected edge");
  }

  @Test
  void currentProjection_reflectsInitialThreeNodes() {
    WorkflowProjection projection = service.currentProjection();

    assertEquals(3, projection.nodes().size(), "projection should contain three nodes");
    assertEquals(0, projection.edges().size(), "initial projection should have no edges");
    assertEquals("workflow.spike", projection.workflowId());
    WorkflowProjection.NodeProjection inputProjection = findNode(projection, INPUT_ID);
    assertEquals(0, inputProjection.inputHandles().size(), "generator has no input handles");
    assertEquals(1, inputProjection.outputHandles().size(), "generator has one output handle");
    assertEquals(SIGNAL_OUT, inputProjection.outputHandles().get(0).id());
    assertEquals(DataTypes.AUDIO_BLOCK.id(), inputProjection.outputHandles().get(0).dataType());
  }

  @Test
  void currentProjection_gainNodeHasTypedHandles() {
    WorkflowProjection.NodeProjection gainProjection = findNode(service.currentProjection(), GAIN_ID);

    assertEquals(1, gainProjection.inputHandles().size(), "gain has one input handle");
    assertEquals(1, gainProjection.outputHandles().size(), "gain has one output handle");
    assertEquals(DataTypes.AUDIO_BLOCK.id(), gainProjection.inputHandles().get(0).dataType());
    assertEquals(DataTypes.AUDIO_BLOCK.id(), gainProjection.outputHandles().get(0).dataType());
  }

  @Test
  void checkpointLoadAndHistory_roundTripChangedGraphThroughStore() {
    VersionedWorkflowStore store = new InMemoryVersionedWorkflowStore();
    WorkflowEditorService persistentService =
        new WorkflowEditorService(log, new WorkflowValidator(), store);
    persistentService.applyOperation(updateGain("op.update.persisted", "2.5"));
    CommitMetadata metadata =
        new CommitMetadata(
            "editor-user", "Checkpoint graph", Instant.parse("2026-01-02T08:00:00Z"));

    CommitId commitId = persistentService.checkpoint("main", metadata);
    persistentService.applyOperation(updateGain("op.update.after.checkpoint", "9.0"));
    WorkflowProjection loaded = persistentService.loadGraph(commitId);

    assertEquals("workflow.spike", loaded.workflowId());
    assertEquals("2.5", findNode(loaded, GAIN_ID).properties().get("gain"));
    assertEquals(0, log.operations().size(), "reload must reset operation history");
    assertEquals(1, persistentService.history("main", 10).size());
    assertEquals(commitId, persistentService.history("main", 10).get(0).commitId());
  }

  @Test
  void loadGraph_branch_loadsHeadAndResetsHistory() {
    VersionedWorkflowStore store = new InMemoryVersionedWorkflowStore();
    WorkflowEditorService persistentService =
        new WorkflowEditorService(log, new WorkflowValidator(), store);
    persistentService.applyOperation(updateGain("op.update.branch", "3.0"));
    persistentService.checkpoint(
        "main",
        new CommitMetadata("editor", "Branch checkpoint", Instant.parse("2026-01-03T00:00:00Z")));
    persistentService.applyOperation(updateGain("op.update.local", "7.0"));

    WorkflowProjection loaded = persistentService.loadGraph("main");

    assertEquals("3.0", findNode(loaded, GAIN_ID).properties().get("gain"));
    assertEquals(0, log.operations().size(), "branch reload must clear local operation history");
  }

  @Test
  void executeSnapshot_usesCurrentWorkflowState() {
    service.applyOperation(updateGain("op.update.gain.snapshot", "2.0"));

    WorkflowSnapshot snapshot = service.executeSnapshot();

    assertEquals("workflow.spike", snapshot.workflowId());
    assertTrue(snapshot.dslText().contains("node.gain"));
    assertTrue(snapshot.dslText().contains("gain: 2.0"));
  }

  @Test
  void validate_reportsCurrentGraphViolations() {
    Node badNode = new Node("node.bad", "bad", "Bad", List.of(), List.of());
    Workflow invalid =
        new Workflow(
            "workflow.invalid",
            "Invalid",
            List.of(badNode),
            List.of(new Edge("edge.bad", "node.missing", "out", "node.bad", "in")));

    WorkflowOperationRejectedException ex =
        assertThrows(WorkflowOperationRejectedException.class, () -> service.loadGraph(invalid));

    assertTrue(ex.violations().stream().anyMatch(v -> v.contains("missing source node")));
  }

  @Test
  void storeBackedMethodsWithoutStore_throwIllegalState() {
    CommitMetadata metadata =
        new CommitMetadata("user", "msg", Instant.parse("2026-01-01T00:00:00Z"));

    assertThrows(IllegalStateException.class, () -> service.checkpoint("main", metadata));
    assertThrows(IllegalStateException.class, () -> service.loadGraph("main"));
    assertThrows(IllegalStateException.class, () -> service.loadGraph(new CommitId("commit.1")));
    assertThrows(IllegalStateException.class, () -> service.history("main", 10));
  }

  @Test
  void inputValidationGuards_rejectBlankOrNegativeArguments() {
    VersionedWorkflowStore store = new InMemoryVersionedWorkflowStore();
    WorkflowEditorService persistentService =
        new WorkflowEditorService(log, new WorkflowValidator(), store);
    CommitMetadata metadata =
        new CommitMetadata("user", "msg", Instant.parse("2026-01-01T00:00:00Z"));

    assertThrows(IllegalArgumentException.class, () -> persistentService.loadGraph(""));
    assertThrows(IllegalArgumentException.class, () -> persistentService.loadGraph("   "));
    assertThrows(IllegalArgumentException.class, () -> persistentService.checkpoint("", metadata));
    assertThrows(IllegalArgumentException.class, () -> persistentService.checkpoint("   ", metadata));
    assertThrows(IllegalArgumentException.class, () -> persistentService.history("", 5));
    assertThrows(IllegalArgumentException.class, () -> persistentService.history("   ", 5));
    assertThrows(IllegalArgumentException.class, () -> persistentService.history("main", -1));
  }

  private static Workflow initialWorkflow() {
    return new Workflow(
        "workflow.spike",
        "Input-Gain-Output Spike",
        List.of(
            ExperimentNodeCatalog.syntheticSignalGenerator(INPUT_ID),
            ExperimentNodeCatalog.gain(GAIN_ID),
            audioOutputNode(OUTPUT_ID)),
        List.of());
  }

  private static Node audioOutputNode(String nodeId) {
    return new Node(
        nodeId,
        "audio-output",
        "Output",
        List.of(
            new Port(
                AUDIO_IN,
                "Audio In",
                PortDirection.INPUT,
                DataTypes.AUDIO_BLOCK,
                true,
                PortMultiplicity.SINGLE)),
        List.of());
  }

  private static WorkflowOperation.UpdateProperty updateGain(String operationId, String value) {
    return new WorkflowOperation.UpdateProperty(
        operationId,
        OP_TIME,
        AUTHOR,
        WorkflowOperation.PropertyTarget.NODE,
        GAIN_ID,
        "gain",
        null,
        value);
  }

  private static WorkflowProjection.NodeProjection findNode(
      WorkflowProjection projection, String nodeId) {
    return projection.nodes().stream()
        .filter(n -> n.id().equals(nodeId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("node missing from projection: " + nodeId));
  }
}
