package org.hammer.audio.workflow.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.hammer.audio.workflow.DataTypes;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Port;
import org.hammer.audio.workflow.PortDirection;
import org.hammer.audio.workflow.PortMultiplicity;
import org.hammer.audio.workflow.Workflow;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link WorkflowDslSerializer} and {@link WorkflowDslParser}.
 *
 * <p>Acceptance criteria (issue #217):
 *
 * <ul>
 *   <li>Serializer produces byte-identical text for equivalent workflows.
 *   <li>Parser can rebuild the workflow model for the minimal graph.
 *   <li>Roundtrip tests exist.
 *   <li>Formatting tests catch non-deterministic output.
 *   <li>Layout/presence are not included in the semantic DSL.
 * </ul>
 */
class WorkflowDslSerializerTest {

  private static final WorkflowDslSerializer SERIALIZER = new WorkflowDslSerializer();
  private static final WorkflowDslParser PARSER = new WorkflowDslParser();

  /** Serializer must produce byte-identical text for equivalent workflows. */
  @Test
  void serializerProducesDeterministicOutput() {
    Workflow workflow = buildMinimalWorkflow();

    String first = SERIALIZER.serialize(workflow);
    String second = SERIALIZER.serialize(workflow);

    assertEquals(first, second);
  }

  /** Nodes are serialized in stable (sorted) order regardless of construction order. */
  @Test
  void serializerSortsNodesByIdForStableOutput() {
    Workflow withAlphaFirst = buildWorkflowWithNodes(true);
    Workflow withBetaFirst = buildWorkflowWithNodes(false);

    String textAlphaFirst = SERIALIZER.serialize(withAlphaFirst);
    String textBetaFirst = SERIALIZER.serialize(withBetaFirst);

    assertEquals(textAlphaFirst, textBetaFirst, "Node ordering must not affect serialized text");
  }

  /** Edges are serialized in stable (sorted) order regardless of construction order. */
  @Test
  void serializerSortsEdgesByIdForStableOutput() {
    Workflow withEdge1First = buildWorkflowWithEdgesOrdered(true);
    Workflow withEdge2First = buildWorkflowWithEdgesOrdered(false);

    String text1 = SERIALIZER.serialize(withEdge1First);
    String text2 = SERIALIZER.serialize(withEdge2First);

    assertEquals(text1, text2, "Edge ordering must not affect serialized text");
  }

  /** Metadata entries are serialized in stable (sorted) order. */
  @Test
  void serializerSortsMetadataKeysForStableOutput() {
    Workflow withZFirst = buildWorkflowWithMetadata(Map.of("z-key", "z-val", "a-key", "a-val"));
    Workflow withAFirst = buildWorkflowWithMetadata(Map.of("a-key", "a-val", "z-key", "z-val"));

    String textZ = SERIALIZER.serialize(withZFirst);
    String textA = SERIALIZER.serialize(withAFirst);

    assertEquals(textZ, textA, "Metadata key ordering must not affect serialized text");
  }

  /** The parser can rebuild the minimal workflow from serialized text. */
  @Test
  void parserRebuildsMinimalWorkflow() {
    Workflow original = buildMinimalWorkflow();

    String text = SERIALIZER.serialize(original);
    Workflow restored = PARSER.parse(text);

    assertEquals(original, restored);
  }

  /** Complete roundtrip with metadata, multiple nodes and edges. */
  @Test
  void fullWorkflowRoundTripsWithoutLoss() {
    Workflow original = buildFullWorkflow();

    String text = SERIALIZER.serialize(original);
    Workflow restored = PARSER.parse(text);

    assertEquals(original, restored);
  }

  /** A workflow with a colon in a metadata value survives roundtrip (quoting test). */
  @Test
  void metadataValueWithSpecialCharactersRoundTrips() {
    Workflow original = buildWorkflowWithMetadata(Map.of("desc", "key: value"));

    String text = SERIALIZER.serialize(original);
    Workflow restored = PARSER.parse(text);

    assertEquals(original.metadata(), restored.metadata());
  }

  /** Literal backslash sequences must remain literal after a serialize/parse roundtrip. */
  @Test
  void metadataValueWithLiteralBackslashNRoundTrips() {
    Workflow original = buildWorkflowWithMetadata(Map.of("path", "C:\\new\\notes"));

    String text = SERIALIZER.serialize(original);
    Workflow restored = PARSER.parse(text);

    assertEquals(original.metadata(), restored.metadata());
  }

  /** Two semantically different workflows must produce different DSL text. */
  @Test
  void differentWorkflowsProduceDifferentText() {
    Workflow workflowA = buildMinimalWorkflow();
    Workflow workflowB =
        new Workflow("workflow.other", "Other Workflow", workflowA.nodes(), workflowA.edges());

    assertNotEquals(SERIALIZER.serialize(workflowA), SERIALIZER.serialize(workflowB));
  }

  /** Parser must reject malformed text. */
  @Test
  void parserRejectsMalformedText() {
    assertThrows(WorkflowDslParseException.class, () -> PARSER.parse("not a workflow\n"));
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private static Workflow buildMinimalWorkflow() {
    // Sorted order: node.sink < node.source
    Node sink =
        new Node(
            "node.sink",
            "report",
            "Sink",
            List.of(
                new Port(
                    "audio-in",
                    "Audio In",
                    PortDirection.INPUT,
                    DataTypes.AUDIO_BLOCK,
                    true,
                    PortMultiplicity.SINGLE)),
            List.of());
    Node source =
        new Node(
            "node.source",
            "recording-input",
            "Source",
            List.of(),
            List.of(
                new Port(
                    "audio-out",
                    "Audio Out",
                    PortDirection.OUTPUT,
                    DataTypes.AUDIO_BLOCK,
                    false,
                    PortMultiplicity.SINGLE)));
    return new Workflow(
        "workflow.minimal",
        "Minimal",
        List.of(sink, source),
        List.of(new Edge("edge.1", "node.source", "audio-out", "node.sink", "audio-in")));
  }

  private static Workflow buildFullWorkflow() {
    // Sorted order: node.sink < node.source
    Node sink =
        new Node(
            "node.sink",
            "report",
            "Sink",
            List.of(
                new Port(
                    "audio-in",
                    "Audio In",
                    PortDirection.INPUT,
                    DataTypes.AUDIO_BLOCK,
                    true,
                    PortMultiplicity.SINGLE)),
            List.of(),
            new Metadata(Map.of("group", "output")));
    Node source =
        new Node(
            "node.source",
            "recording-input",
            "Source",
            List.of(),
            List.of(
                new Port(
                    "audio-out",
                    "Audio Out",
                    PortDirection.OUTPUT,
                    DataTypes.AUDIO_BLOCK,
                    false,
                    PortMultiplicity.SINGLE,
                    new Metadata(Map.of("semanticRole", "primary")))),
            new Metadata(Map.of("group", "input")));
    return new Workflow(
        "workflow.full",
        "Full Workflow",
        List.of(sink, source),
        List.of(new Edge("edge.1", "node.source", "audio-out", "node.sink", "audio-in")),
        new Metadata(Map.of("owner", "test")));
  }

  private static Workflow buildWorkflowWithNodes(boolean alphaFirst) {
    Node alpha =
        new Node(
            "node.alpha",
            "recording-input",
            "Source",
            List.of(),
            List.of(
                new Port(
                    "out",
                    "Out",
                    PortDirection.OUTPUT,
                    DataTypes.AUDIO_BLOCK,
                    false,
                    PortMultiplicity.SINGLE)));
    Node beta =
        new Node(
            "node.beta",
            "report",
            "Sink",
            List.of(
                new Port(
                    "in",
                    "In",
                    PortDirection.INPUT,
                    DataTypes.AUDIO_BLOCK,
                    true,
                    PortMultiplicity.SINGLE)),
            List.of());
    List<Node> nodes = alphaFirst ? List.of(alpha, beta) : List.of(beta, alpha);
    return new Workflow(
        "workflow.test",
        "Test",
        nodes,
        List.of(new Edge("edge.1", "node.alpha", "out", "node.beta", "in")));
  }

  private static Workflow buildWorkflowWithEdgesOrdered(boolean edgeAFirst) {
    Node source =
        new Node(
            "node.source",
            "recording-input",
            "Source",
            List.of(),
            List.of(
                new Port(
                    "out",
                    "Out",
                    PortDirection.OUTPUT,
                    DataTypes.AUDIO_BLOCK,
                    false,
                    PortMultiplicity.MULTI)));
    Node sinkA =
        new Node(
            "node.sinkA",
            "report",
            "SinkA",
            List.of(
                new Port(
                    "in",
                    "In",
                    PortDirection.INPUT,
                    DataTypes.AUDIO_BLOCK,
                    true,
                    PortMultiplicity.SINGLE)),
            List.of());
    Node sinkB =
        new Node(
            "node.sinkB",
            "report",
            "SinkB",
            List.of(
                new Port(
                    "in",
                    "In",
                    PortDirection.INPUT,
                    DataTypes.AUDIO_BLOCK,
                    true,
                    PortMultiplicity.SINGLE)),
            List.of());
    Edge edgeA = new Edge("edge.a", "node.source", "out", "node.sinkA", "in");
    Edge edgeB = new Edge("edge.b", "node.source", "out", "node.sinkB", "in");
    List<Edge> edges = edgeAFirst ? List.of(edgeA, edgeB) : List.of(edgeB, edgeA);
    return new Workflow("workflow.test", "Test", List.of(source, sinkA, sinkB), edges);
  }

  private static Workflow buildWorkflowWithMetadata(Map<String, String> entries) {
    return new Workflow("workflow.meta", "Meta", List.of(), List.of(), new Metadata(entries));
  }
}
