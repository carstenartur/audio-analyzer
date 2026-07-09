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
 * Executable spike tests for {@link WorkflowEditorService} (ADR-007 React Flow + Yjs direction).
 *
 * <p>The test workflow models a minimal {@code Input → Gain → Output} graph using nodes from the
 * experiment node catalog:
 *
 * <ul>
 *   <li><b>Input</b> — {@code SyntheticSignalGenerator} node; outputs one {@code AudioBlock} port.
 *   <li><b>Gain</b> — {@code Gain} node; accepts one {@code AudioBlock} in, produces one {@code
 *       AudioBlock} out.
 *   <li><b>Output</b> — minimal sink node that accepts one {@code AudioBlock} input.
 * </ul>
 *
 * <p>The tests prove:
 *
 * <ol>
 *   <li>A valid edge operation is accepted and reflected in the returned {@code
 *       WorkflowProjection}.
 *   <li>An invalid edge (type mismatch: {@code Dataset} → {@code AudioBlock}) is rejected with
 *       {@link WorkflowOperationRejectedException} and the log is left unchanged.
 *   <li>A parameter update operation is accepted and the property is visible in the projection.
 *   <li>The projection structure (node count, handle lists, edge descriptors) is correct.
 * </ol>
 */
class WorkflowEditorServiceTest {

  private static final Instant OP_TIME = Instant.parse("2026-01-01T12:00:00Z");
  private static final String AUTHOR = "spike-test";

  // Node ids
  private static final String INPUT_ID = "node.input";
  private static final String GAIN_ID = "node.gain";
  private static final String OUTPUT_ID = "node.output";
  private static final String RECORDING_INPUT_ID = "node.recording";

  // Port ids defined in ExperimentNodeCatalog
  private static final String SIGNAL_OUT = "signal-out";
  private static final String AUDIO_IN = "audio-in";
  private static final String AUDIO_OUT = "audio-out";

  /**
   * Minimal sink node that accepts one {@code AudioBlock} input. Represents the "Output" slot in
   * the {@code Input → Gain → Output} demo graph.
   */
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

  private WorkflowEditorService service;
  private WorkflowOperationLog log;

  @BeforeEach
  void setUp() {
    Node inputNode = ExperimentNodeCatalog.syntheticSignalGenerator(INPUT_ID);
    Node gainNode = ExperimentNodeCatalog.gain(GAIN_ID);
    Node outputNode = audioOutputNode(OUTPUT_ID);

    Workflow initial =
        new Workflow(
            "workflow.spike",
            "Input-Gain-Output Spike",
            List.of(inputNode, gainNode, outputNode),
            List.of());

    log = new WorkflowOperationLog(initial);
    service = new WorkflowEditorService(log, new WorkflowValidator());
  }

  // -------------------------------------------------------------------------
  // Valid edge: SyntheticSignalGenerator(AudioBlock) → Gain(AudioBlock)
  // -------------------------------------------------------------------------

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

  // -------------------------------------------------------------------------
  // Invalid edge: RecordingInput(Dataset) → Gain(AudioBlock) — type mismatch
  // -------------------------------------------------------------------------

  @Test
  void invalidEdge_datasetToAudioBlock_rejectedAndLogUnchanged() {
    Node recordingInput = ExperimentNodeCatalog.recordingInput(RECORDING_INPUT_ID);
    Workflow withRecording =
        new Workflow(
            "workflow.spike2",
            "Invalid Edge Spike",
            List.of(
                recordingInput, ExperimentNodeCatalog.gain(GAIN_ID), audioOutputNode(OUTPUT_ID)),
            List.of());
    WorkflowOperationLog rejectionLog = new WorkflowOperationLog(withRecording);
    WorkflowEditorService rejectionService =
        new WorkflowEditorService(rejectionLog, new WorkflowValidator());

    // RecordingInput outputs Dataset; Gain expects AudioBlock — types are incompatible
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
    assertEquals(
        0, rejectionLog.operations().size(), "log must be unchanged after rejected operation");
  }

  // -------------------------------------------------------------------------
  // Parameter update: UpdateProperty on the Gain node
  // -------------------------------------------------------------------------

  @Test
  void parameterUpdate_gainNode_appliedAndVisibleInProjection() {
    WorkflowOperation updateOp =
        new WorkflowOperation.UpdateProperty(
            "op.update.gain",
            OP_TIME,
            AUTHOR,
            WorkflowOperation.PropertyTarget.NODE,
            GAIN_ID,
            "gain",
            null,
            "1.5");

    WorkflowProjection projection = service.applyOperation(updateOp);

    assertEquals(1, log.operations().size(), "update operation should be recorded in the log");
    WorkflowProjection.NodeProjection gainProjection =
        projection.nodes().stream()
            .filter(n -> n.id().equals(GAIN_ID))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Gain node missing from projection"));
    assertEquals(GAIN_ID, gainProjection.id());
    assertEquals("gain", gainProjection.type());
    assertEquals(
        "1.5",
        gainProjection.properties().get("gain"),
        "gain property value must be visible in the projection after UpdateProperty");
  }

  // -------------------------------------------------------------------------
  // Edge removal: DisconnectPorts path through WorkflowEditorService
  // -------------------------------------------------------------------------

  @Test
  void disconnectPorts_existingEdge_removedAndNotInProjection() {
    Edge edge = new Edge("edge.input-to-gain", INPUT_ID, SIGNAL_OUT, GAIN_ID, AUDIO_IN);
    WorkflowOperation connectOp =
        new WorkflowOperation.ConnectPorts("op.connect", OP_TIME, AUTHOR, edge);
    service.applyOperation(connectOp);

    WorkflowOperation disconnectOp =
        new WorkflowOperation.DisconnectPorts("op.disconnect", OP_TIME, AUTHOR, edge.id(), edge);
    WorkflowProjection projection = service.applyOperation(disconnectOp);

    assertEquals(
        0, projection.edges().size(), "projection must not contain the edge after DisconnectPorts");
  }

  @Test
  void disconnectPorts_recordedInLogOnlyAfterValidation() {
    Edge edge = new Edge("edge.input-to-gain", INPUT_ID, SIGNAL_OUT, GAIN_ID, AUDIO_IN);
    WorkflowOperation connectOp =
        new WorkflowOperation.ConnectPorts("op.connect", OP_TIME, AUTHOR, edge);
    service.applyOperation(connectOp);
    assertEquals(1, log.operations().size(), "connect operation should be recorded");

    WorkflowOperation disconnectOp =
        new WorkflowOperation.DisconnectPorts("op.disconnect", OP_TIME, AUTHOR, edge.id(), edge);
    service.applyOperation(disconnectOp);

    assertEquals(
        2,
        log.operations().size(),
        "disconnect operation should be recorded in the log only after validation passes");
  }

  // -------------------------------------------------------------------------
  // Projection structure: node count, handle lists, initial empty edges
  // -------------------------------------------------------------------------

  @Test
  void currentProjection_reflectsInitialThreeNodes() {
    WorkflowProjection projection = service.currentProjection();

    assertEquals(3, projection.nodes().size(), "projection should contain three nodes");
    assertEquals(0, projection.edges().size(), "initial projection should have no edges");
    assertEquals("workflow.spike", projection.workflowId());

    WorkflowProjection.NodeProjection inputProjection =
        projection.nodes().stream()
            .filter(n -> n.id().equals(INPUT_ID))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Input node missing from projection"));

    assertEquals(0, inputProjection.inputHandles().size(), "generator has no input handles");
    assertEquals(1, inputProjection.outputHandles().size(), "generator has one output handle");

    WorkflowProjection.HandleProjection signalOutHandle = inputProjection.outputHandles().get(0);
    assertEquals(SIGNAL_OUT, signalOutHandle.id());
    assertEquals(DataTypes.AUDIO_BLOCK.id(), signalOutHandle.dataType());
  }

  @Test
  void currentProjection_gainNodeHasTypedHandles() {
    WorkflowProjection projection = service.currentProjection();

    WorkflowProjection.NodeProjection gainProjection =
        projection.nodes().stream()
            .filter(n -> n.id().equals(GAIN_ID))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Gain node missing from projection"));

    assertEquals(1, gainProjection.inputHandles().size(), "gain has one input handle");
    assertEquals(1, gainProjection.outputHandles().size(), "gain has one output handle");
    assertEquals(
        DataTypes.AUDIO_BLOCK.id(),
        gainProjection.inputHandles().get(0).dataType(),
        "gain input handle must be AudioBlock");
    assertEquals(
        DataTypes.AUDIO_BLOCK.id(),
        gainProjection.outputHandles().get(0).dataType(),
        "gain output handle must be AudioBlock");
  }

  @Test
  void checkpointLoadAndHistory_roundTripThroughStore() {
    VersionedWorkflowStore store = new InMemoryVersionedWorkflowStore();
    WorkflowEditorService persistentService =
        new WorkflowEditorService(log, new WorkflowValidator(), store);
    CommitMetadata metadata =
        new CommitMetadata(
            "editor-user", "Checkpoint graph", Instant.parse("2026-01-02T08:00:00Z"));

    CommitId commitId = persistentService.checkpoint("main", metadata);
    WorkflowProjection loaded = persistentService.loadGraph(commitId);

    assertEquals("workflow.spike", loaded.workflowId());
    assertEquals(1, persistentService.history("main", 10).size());
    assertEquals(commitId, persistentService.history("main", 10).get(0).commitId());
  }

  @Test
  void executeSnapshot_usesCurrentWorkflowState() {
    WorkflowOperation updateOp =
        new WorkflowOperation.UpdateProperty(
            "op.update.gain.snapshot",
            OP_TIME,
            AUTHOR,
            WorkflowOperation.PropertyTarget.NODE,
            GAIN_ID,
            "gain",
            null,
            "2.0");
    service.applyOperation(updateOp);

    WorkflowSnapshot snapshot = service.executeSnapshot();

    assertEquals("workflow.spike", snapshot.workflowId());
    assertTrue(snapshot.dslText().contains("workflow"));
    assertTrue(snapshot.dslText().contains("node.gain"));
  }
}
