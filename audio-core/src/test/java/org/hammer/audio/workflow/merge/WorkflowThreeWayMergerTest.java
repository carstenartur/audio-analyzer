package org.hammer.audio.workflow.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Port;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.catalog.ExperimentNodeCatalog;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
import org.hammer.audio.workflow.merge.WorkflowDiffModels.ElementKind;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.Conflict;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.ConflictKind;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.Preview;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.Resolution;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.ResolutionChoice;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.Result;
import org.junit.jupiter.api.Test;

class WorkflowThreeWayMergerTest {

  private final WorkflowThreeWayMerger merger = new WorkflowThreeWayMerger();

  @Test
  void automaticallyMergesIndependentChangesWithByteEquivalentDsl() {
    Workflow base = connectedWorkflow();
    Node localGenerator = withLabel(node(base, "node.generator"), "Local signal");
    Workflow local = replaceNode(base, localGenerator);
    Node remoteGain =
        withMetadata(node(base, "node.gain"), Map.of("gain.factor", "0.75"));
    Workflow remote = replaceNode(base, remoteGain);

    Preview first = merger.preview(base, local, remote);
    Preview second = merger.preview(base, local, remote);
    Result result = merger.resolve(base, local, remote, List.of());

    assertTrue(first.readyToCommit());
    assertTrue(result.readyToCommit());
    assertEquals("Local signal", node(result.workflow(), "node.generator").label());
    assertEquals(
        "0.75", node(result.workflow(), "node.gain").metadata().entries().get("gain.factor"));
    WorkflowDslSerializer serializer = new WorkflowDslSerializer();
    assertEquals(
        serializer.serialize(first.autoMergedWorkflow()),
        serializer.serialize(second.autoMergedWorkflow()));
  }

  @Test
  void exposesDivergentScalarValuesAndSupportsExplicitCustomResolution() {
    Workflow base = connectedWorkflow();
    Workflow local =
        replaceNode(base, withLabel(node(base, "node.generator"), "Local signal"));
    Workflow remote =
        replaceNode(base, withLabel(node(base, "node.generator"), "Remote signal"));

    Preview preview = merger.preview(base, local, remote);
    Conflict conflict = preview.conflicts().getFirst();
    Result resolved =
        merger.resolve(
            base,
            local,
            remote,
            List.of(new Resolution(conflict.conflictId(), ResolutionChoice.CUSTOM, "Merged signal")));

    assertEquals(ConflictKind.DIVERGENT_VALUE, conflict.kind());
    assertEquals(ElementKind.NODE, conflict.elementKind());
    assertEquals("node.generator", conflict.elementId());
    assertEquals("label", conflict.fieldPath());
    assertTrue(conflict.allowedChoices().contains(ResolutionChoice.CUSTOM));
    assertEquals(
        "Synthetic Signal Generator",
        node(preview.autoMergedWorkflow(), conflict.elementId()).label());
    assertTrue(resolved.readyToCommit());
    assertEquals("Merged signal", node(resolved.workflow(), conflict.elementId()).label());
  }

  @Test
  void reportsDeleteVersusModifyWithoutSilentlySelectingEitherBranch() {
    Workflow base = connectedWorkflow();
    Workflow local =
        new Workflow(
            base.id(),
            base.name(),
            List.of(node(base, "node.generator")),
            List.of(),
            base.metadata());
    Workflow remote = replaceNode(base, withLabel(node(base, "node.gain"), "Remote gain"));

    Preview preview = merger.preview(base, local, remote);
    Conflict conflict = preview.conflicts().getFirst();
    Result deleted =
        merger.resolve(
            base,
            local,
            remote,
            List.of(new Resolution(conflict.conflictId(), ResolutionChoice.DELETE)));

    assertEquals(ConflictKind.DELETE_MODIFY, conflict.kind());
    assertEquals("node.gain", conflict.elementId());
    assertFalse(deleted.workflow().nodes().stream().anyMatch(value -> value.id().equals("node.gain")));
    assertTrue(deleted.readyToCommit());
  }

  @Test
  void treatsDeleteVersusNewConnectionAsOneNodeNeighborhoodConflict() {
    Workflow base = disconnectedWorkflow();
    Workflow local =
        new Workflow(
            base.id(),
            base.name(),
            List.of(node(base, "node.generator")),
            List.of(),
            base.metadata());
    Edge remoteEdge = signalToGain(node(base, "node.generator"), node(base, "node.gain"));
    Workflow remote =
        new Workflow(base.id(), base.name(), base.nodes(), List.of(remoteEdge), base.metadata());

    Preview preview = merger.preview(base, local, remote);
    Conflict conflict = preview.conflicts().getFirst();
    Result remoteResolution =
        merger.resolve(
            base,
            local,
            remote,
            List.of(new Resolution(conflict.conflictId(), ResolutionChoice.REMOTE)));

    assertEquals(ConflictKind.DELETE_CONNECT, conflict.kind());
    assertEquals("connections", conflict.fieldPath());
    assertEquals(0, preview.autoMergedWorkflow().edges().size());
    assertEquals(1, remoteResolution.workflow().edges().size());
    assertTrue(remoteResolution.readyToCommit());
  }

  @Test
  void reportsStableNodeIdCollisionAndResolvesTheWholeNodeSnapshot() {
    Workflow base =
        new Workflow(
            "workflow.merge",
            "Base workflow",
            List.of(ExperimentNodeCatalog.syntheticSignalGenerator("node.generator")),
            List.of());
    Node localGain = withLabel(ExperimentNodeCatalog.gain("node.gain"), "Local gain");
    Node remoteGain = withLabel(ExperimentNodeCatalog.gain("node.gain"), "Remote gain");
    Workflow local =
        new Workflow(base.id(), base.name(), List.of(base.nodes().getFirst(), localGain), List.of());
    Workflow remote =
        new Workflow(base.id(), base.name(), List.of(base.nodes().getFirst(), remoteGain), List.of());

    Preview preview = merger.preview(base, local, remote);
    Conflict conflict = preview.conflicts().getFirst();
    Result localResolution =
        merger.resolve(
            base,
            local,
            remote,
            List.of(new Resolution(conflict.conflictId(), ResolutionChoice.LOCAL)));

    assertEquals(ConflictKind.STABLE_ID_COLLISION, conflict.kind());
    assertFalse(
        preview.autoMergedWorkflow().nodes().stream()
            .anyMatch(value -> value.id().equals("node.gain")));
    assertEquals("Local gain", node(localResolution.workflow(), "node.gain").label());
    assertTrue(localResolution.readyToCommit());
  }

  @Test
  void reportsDivergentEdgeEndpointsAsOneSemanticConflict() {
    Node generatorOne = ExperimentNodeCatalog.syntheticSignalGenerator("node.generator.one");
    Node generatorTwo = ExperimentNodeCatalog.syntheticSignalGenerator("node.generator.two");
    Node gainOne = ExperimentNodeCatalog.gain("node.gain.one");
    Node gainTwo = ExperimentNodeCatalog.gain("node.gain.two");
    Edge baseEdge = signalToGain("edge.route", generatorOne, gainOne);
    Workflow base =
        new Workflow(
            "workflow.merge",
            "Endpoint workflow",
            List.of(generatorOne, generatorTwo, gainOne, gainTwo),
            List.of(baseEdge));
    Workflow local =
        new Workflow(
            base.id(),
            base.name(),
            base.nodes(),
            List.of(signalToGain("edge.route", generatorTwo, gainOne)));
    Workflow remote =
        new Workflow(
            base.id(),
            base.name(),
            base.nodes(),
            List.of(signalToGain("edge.route", generatorOne, gainTwo)));

    Preview preview = merger.preview(base, local, remote);
    Conflict conflict = preview.conflicts().getFirst();
    Result remoteResolution =
        merger.resolve(
            base,
            local,
            remote,
            List.of(new Resolution(conflict.conflictId(), ResolutionChoice.REMOTE)));

    assertEquals(ConflictKind.DIVERGENT_EDGE_ENDPOINTS, conflict.kind());
    assertEquals("endpoints", conflict.fieldPath());
    assertEquals("node.gain.two", remoteResolution.workflow().edges().getFirst().targetNodeId());
    assertTrue(remoteResolution.readyToCommit());
  }

  @Test
  void reportsValidatorImpactWhenIndependentChangesCreateAnInvalidGraph() {
    Workflow base = disconnectedWorkflow();
    Node generator = node(base, "node.generator");
    Port original = generator.outputPorts().getFirst();
    Port renamedPort =
        new Port(
            "signal-out-v2",
            original.name(),
            original.direction(),
            original.dataType(),
            original.required(),
            original.multiplicity(),
            original.metadata());
    Node localGenerator =
        new Node(
            generator.id(),
            generator.type(),
            generator.label(),
            generator.inputPorts(),
            List.of(renamedPort),
            generator.metadata());
    Workflow local = replaceNode(base, localGenerator);
    Workflow remote =
        new Workflow(
            base.id(),
            base.name(),
            base.nodes(),
            List.of(signalToGain(generator, node(base, "node.gain"))),
            base.metadata());

    Preview preview = merger.preview(base, local, remote);
    Result result = merger.resolve(base, local, remote, List.of());

    assertTrue(preview.conflictFree());
    assertFalse(preview.readyToCommit());
    assertTrue(
        preview.validationViolations().stream()
            .anyMatch(message -> message.contains("missing source port")));
    assertFalse(result.readyToCommit());
  }

  @Test
  void ordersWorkflowConflictsBeforeNodeConflictsAndRejectsUnknownResolutions() {
    Workflow base = connectedWorkflow();
    Workflow local =
        new Workflow(
            base.id(),
            "Local workflow",
            List.of(
                withLabel(node(base, "node.generator"), "Local signal"),
                node(base, "node.gain")),
            base.edges(),
            base.metadata());
    Workflow remote =
        new Workflow(
            base.id(),
            "Remote workflow",
            List.of(
                withLabel(node(base, "node.generator"), "Remote signal"),
                node(base, "node.gain")),
            base.edges(),
            base.metadata());

    Preview preview = merger.preview(base, local, remote);

    assertEquals(ElementKind.WORKFLOW, preview.conflicts().getFirst().elementKind());
    assertEquals(ElementKind.NODE, preview.conflicts().get(1).elementKind());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            merger.resolve(
                base,
                local,
                remote,
                List.of(new Resolution("unknown-conflict", ResolutionChoice.LOCAL))));
  }

  private static Workflow connectedWorkflow() {
    Workflow workflow = disconnectedWorkflow();
    return new Workflow(
        workflow.id(),
        workflow.name(),
        workflow.nodes(),
        List.of(signalToGain(node(workflow, "node.generator"), node(workflow, "node.gain"))),
        workflow.metadata());
  }

  private static Workflow disconnectedWorkflow() {
    return new Workflow(
        "workflow.merge",
        "Base workflow",
        List.of(
            ExperimentNodeCatalog.syntheticSignalGenerator("node.generator"),
            ExperimentNodeCatalog.gain("node.gain")),
        List.of(),
        new Metadata(Map.of("owner", "team")));
  }

  private static Edge signalToGain(Node generator, Node gain) {
    return signalToGain("edge.signal-gain", generator, gain);
  }

  private static Edge signalToGain(String edgeId, Node generator, Node gain) {
    return new Edge(edgeId, generator.id(), "signal-out", gain.id(), "audio-in");
  }

  private static Node node(Workflow workflow, String nodeId) {
    return workflow.nodes().stream().filter(value -> value.id().equals(nodeId)).findFirst().orElseThrow();
  }

  private static Node withLabel(Node node, String label) {
    return new Node(
        node.id(),
        node.type(),
        label,
        node.inputPorts(),
        node.outputPorts(),
        node.metadata());
  }

  private static Node withMetadata(Node node, Map<String, String> entries) {
    return new Node(
        node.id(),
        node.type(),
        node.label(),
        node.inputPorts(),
        node.outputPorts(),
        new Metadata(entries));
  }

  private static Workflow replaceNode(Workflow workflow, Node replacement) {
    List<Node> nodes =
        workflow.nodes().stream()
            .map(node -> node.id().equals(replacement.id()) ? replacement : node)
            .toList();
    return new Workflow(
        workflow.id(), workflow.name(), nodes, workflow.edges(), workflow.metadata());
  }
}
