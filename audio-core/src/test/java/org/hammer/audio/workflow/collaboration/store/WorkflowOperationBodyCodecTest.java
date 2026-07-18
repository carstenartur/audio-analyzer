package org.hammer.audio.workflow.collaboration.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.hammer.audio.workflow.DataType;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Port;
import org.hammer.audio.workflow.PortDirection;
import org.hammer.audio.workflow.PortMultiplicity;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.WorkflowOperation.PropertyTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class WorkflowOperationBodyCodecTest {

  private static final Instant OCCURRED_AT = Instant.parse("2026-07-18T20:30:00.123456Z");
  private static final Port OUTPUT_PORT =
      new Port(
          "audio.out",
          "Audio",
          PortDirection.OUTPUT,
          new DataType("audio.block"),
          true,
          PortMultiplicity.SINGLE,
          new Metadata(Map.of("unit", "normalized")));
  private static final Port INPUT_PORT =
      new Port(
          "audio.in",
          "Audio",
          PortDirection.INPUT,
          new DataType("audio.block"),
          true,
          PortMultiplicity.SINGLE,
          Metadata.empty());
  private static final Node SOURCE_NODE =
      new Node(
          "node.source",
          "generator",
          "Generator",
          List.of(),
          List.of(OUTPUT_PORT),
          new Metadata(Map.of("layout.x", "10.5")));
  private static final Node TARGET_NODE =
      new Node(
          "node.target",
          "gain",
          "Gain",
          List.of(INPUT_PORT),
          List.of(OUTPUT_PORT),
          new Metadata(Map.of("gain", "1.5")));
  private static final Edge EDGE =
      new Edge(
          "edge.audio",
          SOURCE_NODE.id(),
          OUTPUT_PORT.id(),
          TARGET_NODE.id(),
          INPUT_PORT.id(),
          new Metadata(Map.of("label", "audio")));

  @ParameterizedTest
  @MethodSource("operations")
  void roundTripsEverySemanticOperation(WorkflowOperation operation) {
    WorkflowOperationBodyCodec.EncodedBody encoded = WorkflowOperationBodyCodec.encode(operation);

    WorkflowOperation decoded = WorkflowOperationBodyCodec.decode(encoded.version(), encoded.body());

    assertEquals(operation, decoded);
    assertEquals(encoded, WorkflowOperationBodyCodec.encode(decoded));
  }

  @Test
  void reidentifiesInverseWithoutChangingItsSemanticBody() {
    WorkflowOperation original =
        new WorkflowOperation.DisconnectPorts(
            "operation.disconnect", OCCURRED_AT, "actor.original", EDGE.id(), EDGE);
    WorkflowOperation inverse = original.inverseOperation().orElseThrow();
    Instant undoTime = Instant.parse("2026-07-18T20:31:00Z");

    WorkflowOperation reidentified =
        WorkflowOperationBodyCodec.reidentify(
            inverse, "command.undo.1:operation", undoTime, "actor.undo");

    assertEquals(
        new WorkflowOperation.ConnectPorts(
            "command.undo.1:operation", undoTime, "actor.undo", EDGE),
        reidentified);
  }

  @Test
  void rejectsUnknownVersionAndMalformedBody() {
    WorkflowOperationBodyCodec.EncodedBody encoded =
        WorkflowOperationBodyCodec.encode(
            new WorkflowOperation.CreateNode(
                "operation.create", OCCURRED_AT, "actor.one", SOURCE_NODE));

    assertThrows(
        IllegalArgumentException.class,
        () -> WorkflowOperationBodyCodec.decode(encoded.version() + 1, encoded.body()));
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkflowOperationBodyCodec.decode(encoded.version(), "not-base64!"));
  }

  private static Stream<WorkflowOperation> operations() {
    return Stream.of(
        new WorkflowOperation.CreateNode("operation.create", OCCURRED_AT, "actor.one", SOURCE_NODE),
        new WorkflowOperation.DeleteNode(
            "operation.delete",
            OCCURRED_AT,
            "actor.one",
            TARGET_NODE,
            List.of(EDGE),
            List.of(TARGET_NODE.id(), EDGE.id())),
        new WorkflowOperation.MoveNode(
            "operation.move", OCCURRED_AT, "actor.one", SOURCE_NODE.id(), 1.25, 2.5, 3.75, 4.0),
        new WorkflowOperation.RenameNode(
            "operation.rename",
            OCCURRED_AT,
            "actor.one",
            SOURCE_NODE.id(),
            "Generator",
            "Signal generator"),
        new WorkflowOperation.ConnectPorts("operation.connect", OCCURRED_AT, "actor.one", EDGE),
        new WorkflowOperation.DisconnectPorts(
            "operation.disconnect", OCCURRED_AT, "actor.one", EDGE.id(), EDGE),
        new WorkflowOperation.UpdateProperty(
            "operation.property",
            OCCURRED_AT,
            "actor.one",
            PropertyTarget.NODE,
            TARGET_NODE.id(),
            "gain",
            "1.5",
            null),
        new WorkflowOperation.GroupNodes(
            "operation.group",
            OCCURRED_AT,
            "actor.one",
            "group.analysis",
            "Analysis",
            List.of(SOURCE_NODE.id(), TARGET_NODE.id()),
            Map.of(SOURCE_NODE.id(), WorkflowOperation.NO_GROUP, TARGET_NODE.id(), "group.old")),
        new WorkflowOperation.UngroupNodes(
            "operation.ungroup",
            OCCURRED_AT,
            "actor.one",
            "group.analysis",
            "Analysis",
            List.of(SOURCE_NODE.id(), TARGET_NODE.id()),
            Map.of(SOURCE_NODE.id(), WorkflowOperation.NO_GROUP, TARGET_NODE.id(), "group.old")),
        new WorkflowOperation.RestoreNode(
            "operation.restore", OCCURRED_AT, "actor.one", TARGET_NODE, List.of(EDGE)));
  }
}
