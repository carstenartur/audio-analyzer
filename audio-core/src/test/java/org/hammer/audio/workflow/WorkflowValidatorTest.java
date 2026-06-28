package org.hammer.audio.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowValidatorTest {

  private final WorkflowValidator validator = new WorkflowValidator();

  @Test
  void acceptsStructurallyValidWorkflow() {
    Workflow workflow =
        new Workflow(
            "workflow.valid",
            "Valid Workflow",
            List.of(
                new Node(
                    "node.source",
                    "source",
                    "Source",
                    List.of(),
                    List.of(
                        new Port(
                            "source-out",
                            "output",
                            PortDirection.OUTPUT,
                            "Dataset",
                            true,
                            PortMultiplicity.SINGLE,
                            new Metadata(Map.of("stage", "source"))))),
                new Node(
                    "node.sink",
                    "sink",
                    "Sink",
                    List.of(
                        new Port(
                            "sink-in",
                            "input",
                            PortDirection.INPUT,
                            "Dataset",
                            true,
                            PortMultiplicity.SINGLE)),
                    List.of())),
            List.of(
                new Edge(
                    "edge.source-to-sink", "node.source", "source-out", "node.sink", "sink-in")));

    assertTrue(validator.isValid(workflow));
    assertEquals(List.of(), validator.validate(workflow));
  }

  @Test
  void reportsDuplicateIdsAndBrokenEdgeReferences() {
    Workflow workflow =
        new Workflow(
            "workflow.invalid",
            "Invalid Workflow",
            List.of(
                new Node(
                    "node.shared",
                    "producer",
                    "Producer",
                    List.of(),
                    List.of(
                        new Port(
                            "port.shared",
                            "output",
                            PortDirection.OUTPUT,
                            "Dataset",
                            true,
                            PortMultiplicity.SINGLE),
                        new Port(
                            "port.shared",
                            "output-copy",
                            PortDirection.OUTPUT,
                            "Dataset",
                            true,
                            PortMultiplicity.MULTI))),
                new Node(
                    "node.shared",
                    "consumer",
                    "Consumer",
                    List.of(
                        new Port(
                            "port.in",
                            "input",
                            PortDirection.OUTPUT,
                            "Dataset",
                            true,
                            PortMultiplicity.SINGLE)),
                    List.of())),
            List.of(
                new Edge("edge.shared", "node.shared", "missing-port", "node.shared", "port.in"),
                new Edge(
                    "edge.shared",
                    "node.missing",
                    "port.shared",
                    "node.shared",
                    "missing-target")));

    List<String> violations = validator.validate(workflow);

    assertEquals(
        List.of(
            "Node node.shared has duplicate port id: port.shared",
            "Duplicate node id: node.shared",
            "Node node.shared has inputPorts port port.in with direction OUTPUT",
            "Edge edge.shared references missing source port node.shared:missing-port",
            "Edge edge.shared references missing target port node.shared:port.in",
            "Duplicate edge id: edge.shared",
            "Edge edge.shared references missing source node node.missing"),
        violations);
  }

  @Test
  void reportsDuplicatePortIdsAcrossPortCollections() {
    Workflow workflow =
        new Workflow(
            "workflow.duplicate-port",
            "Duplicate Port Workflow",
            List.of(
                new Node(
                    "node.shared-port",
                    "transform",
                    "Transform",
                    List.of(
                        new Port(
                            "port.shared",
                            "input",
                            PortDirection.INPUT,
                            "Dataset",
                            true,
                            PortMultiplicity.SINGLE)),
                    List.of(
                        new Port(
                            "port.shared",
                            "output",
                            PortDirection.OUTPUT,
                            "Dataset",
                            true,
                            PortMultiplicity.SINGLE)))),
            List.of());

    assertEquals(
        List.of("Node node.shared-port has duplicate port id: port.shared"),
        validator.validate(workflow));
  }

  @Test
  void rejectsIncompatiblePortConnectionTypes() {
    Workflow workflow =
        new Workflow(
            "workflow.incompatible-types",
            "Incompatible Types",
            List.of(
                new Node(
                    "node.dataset",
                    "source",
                    "Dataset Source",
                    List.of(),
                    List.of(
                        new Port(
                            "dataset-out",
                            "dataset",
                            PortDirection.OUTPUT,
                            "Dataset",
                            true,
                            PortMultiplicity.SINGLE))),
                new Node(
                    "node.report",
                    "sink",
                    "Report Sink",
                    List.of(
                        new Port(
                            "report-in",
                            "report",
                            PortDirection.INPUT,
                            "Report",
                            true,
                            PortMultiplicity.SINGLE)),
                    List.of())),
            List.of(
                new Edge(
                    "edge.bad-types", "node.dataset", "dataset-out", "node.report", "report-in")));

    assertEquals(
        List.of("Edge edge.bad-types connects incompatible data types Dataset -> Report"),
        validator.validate(workflow));
  }

  @Test
  void rejectsUnknownPortDataTypes() {
    Workflow workflow =
        new Workflow(
            "workflow.unknown-type",
            "Unknown Type",
            List.of(
                new Node(
                    "node.source",
                    "source",
                    "Source",
                    List.of(),
                    List.of(
                        new Port(
                            "out",
                            "output",
                            PortDirection.OUTPUT,
                            "CustomType",
                            true,
                            PortMultiplicity.SINGLE))),
                new Node(
                    "node.sink",
                    "sink",
                    "Sink",
                    List.of(
                        new Port(
                            "in",
                            "input",
                            PortDirection.INPUT,
                            "CustomType",
                            true,
                            PortMultiplicity.SINGLE)),
                    List.of())),
            List.of(new Edge("edge.custom", "node.source", "out", "node.sink", "in")));

    assertEquals(
        List.of(
            "Node node.source has outputPorts port out with unknown data type CustomType",
            "Node node.sink has inputPorts port in with unknown data type CustomType",
            "Edge edge.custom connects incompatible data types CustomType -> CustomType"),
        validator.validate(workflow));
  }

  @Test
  void rejectsNullWorkflow() {
    NullPointerException exception =
        assertThrows(NullPointerException.class, () -> validator.validate(null));

    assertEquals("workflow", exception.getMessage());
  }
}
