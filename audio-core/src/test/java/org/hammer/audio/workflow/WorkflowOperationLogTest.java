package org.hammer.audio.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowOperationLogTest {

  private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

  @Test
  void appliesSemanticOperationsAndKeepsOperationMetadata() {
    Workflow initial = initialWorkflow();
    WorkflowOperationLog log = new WorkflowOperationLog(initial);

    Node nodeAnalyze =
        new Node(
            "node.analyze",
            "analyze",
            "Analyze",
            List.of(
                new Port(
                    "dataset-in",
                    "input",
                    PortDirection.INPUT,
                    "Dataset",
                    true,
                    PortMultiplicity.SINGLE)),
            List.of(
                new Port(
                    "report-out",
                    "output",
                    PortDirection.OUTPUT,
                    "Report",
                    true,
                    PortMultiplicity.SINGLE)));
    Edge edgeAnalyzeToReport =
        new Edge(
            "edge.analyze-to-report", "node.analyze", "report-out", "node.report", "report-in");

    List<WorkflowOperation> operations =
        List.of(
            new WorkflowOperation.CreateNode("op.create", NOW, "alice", nodeAnalyze),
            new WorkflowOperation.MoveNode(
                "op.move", NOW.plusSeconds(1), "alice", "node.analyze", 0.0, 0.0, 10.5, 20.5),
            new WorkflowOperation.RenameNode(
                "op.rename",
                NOW.plusSeconds(2),
                "alice",
                "node.analyze",
                "Analyze",
                "Analyze Dataset"),
            new WorkflowOperation.ConnectPorts(
                "op.connect", NOW.plusSeconds(3), "alice", edgeAnalyzeToReport),
            new WorkflowOperation.UpdateProperty(
                "op.property",
                NOW.plusSeconds(4),
                "alice",
                WorkflowOperation.PropertyTarget.NODE,
                "node.analyze",
                "note",
                null,
                "important"),
            new WorkflowOperation.GroupNodes(
                "op.group",
                NOW.plusSeconds(5),
                "alice",
                "group.processing",
                "Processing",
                List.of("node.dataset", "node.analyze"),
                nullableGroupMap("node.dataset", null, "node.analyze", null)),
            new WorkflowOperation.UngroupNodes(
                "op.ungroup",
                NOW.plusSeconds(6),
                "alice",
                "group.processing",
                "Processing",
                List.of("node.dataset", "node.analyze"),
                nullableGroupMap("node.dataset", null, "node.analyze", null)),
            new WorkflowOperation.DisconnectPorts(
                "op.disconnect",
                NOW.plusSeconds(7),
                "alice",
                edgeAnalyzeToReport.id(),
                edgeAnalyzeToReport),
            new WorkflowOperation.DeleteNode(
                "op.delete",
                NOW.plusSeconds(8),
                "alice",
                nodeAnalyze,
                List.of(),
                List.of("node.analyze")));

    for (WorkflowOperation operation : operations) {
      log.apply(operation);
      assertFalse(operation.payload().isEmpty());
      assertFalse(operation.affectedObjectIds().isEmpty());
      assertTrue(operation.inverseOperation().isPresent());
    }

    Workflow result = log.currentWorkflow();
    assertEquals(initial.nodes(), result.nodes());
    assertEquals(initial.edges(), result.edges());
    assertEquals(operations, log.operations());
  }

  @Test
  void replaysOperationsDeterministically() {
    WorkflowOperation create =
        new WorkflowOperation.CreateNode(
            "op.create",
            NOW,
            "alice",
            new Node(
                "node.transform",
                "transform",
                "Transform",
                List.of(
                    new Port(
                        "dataset-in",
                        "input",
                        PortDirection.INPUT,
                        "Dataset",
                        true,
                        PortMultiplicity.SINGLE)),
                List.of(
                    new Port(
                        "dataset-out",
                        "output",
                        PortDirection.OUTPUT,
                        "Dataset",
                        true,
                        PortMultiplicity.SINGLE))));
    WorkflowOperation rename =
        new WorkflowOperation.RenameNode(
            "op.rename",
            NOW.plusSeconds(1),
            "alice",
            "node.transform",
            "Transform",
            "Transform Dataset");

    WorkflowOperationLog firstLog = new WorkflowOperationLog(initialWorkflow());
    firstLog.apply(create);
    firstLog.apply(rename);

    WorkflowOperationLog secondLog = new WorkflowOperationLog(initialWorkflow());
    for (WorkflowOperation operation : firstLog.operations()) {
      secondLog.apply(operation);
    }

    assertEquals(firstLog.currentWorkflow(), firstLog.replay());
    assertEquals(firstLog.currentWorkflow(), secondLog.currentWorkflow());
  }

  @Test
  void undoesLastOperationThroughInverseOperation() {
    Workflow initial = initialWorkflow();
    WorkflowOperationLog log = new WorkflowOperationLog(initial);

    Node reportNode = findNode(initial, "node.report");
    Edge edge = findEdge(initial, "edge.dataset-to-report");
    WorkflowOperation delete =
        new WorkflowOperation.DeleteNode(
            "op.delete",
            NOW,
            "alice",
            reportNode,
            List.of(edge),
            List.of("node.report", "edge.dataset-to-report"));

    log.apply(delete);
    assertEquals(1, log.currentWorkflow().nodes().size());
    assertEquals(0, log.currentWorkflow().edges().size());

    Workflow undone = log.undoLast();
    assertEquals(initial, undone);
    assertEquals(List.of(), log.operations());
  }

  @Test
  void undoFailsWhenNoOperationExists() {
    WorkflowOperationLog log = new WorkflowOperationLog(initialWorkflow());

    IllegalStateException exception = assertThrows(IllegalStateException.class, log::undoLast);

    assertEquals("No operation available to undo", exception.getMessage());
  }

  private static Workflow initialWorkflow() {
    return new Workflow(
        "workflow.demo",
        "Demo Workflow",
        List.of(
            new Node(
                "node.dataset",
                "dataset",
                "Dataset",
                List.of(),
                List.of(
                    new Port(
                        "dataset-out",
                        "output",
                        PortDirection.OUTPUT,
                        "Dataset",
                        true,
                        PortMultiplicity.SINGLE))),
            new Node(
                "node.report",
                "report",
                "Report",
                List.of(
                    new Port(
                        "report-in",
                        "input",
                        PortDirection.INPUT,
                        "Dataset",
                        true,
                        PortMultiplicity.SINGLE)),
                List.of())),
        List.of(
            new Edge(
                "edge.dataset-to-report",
                "node.dataset",
                "dataset-out",
                "node.report",
                "report-in")));
  }

  private static Node findNode(Workflow workflow, String nodeId) {
    return workflow.nodes().stream()
        .filter(node -> node.id().equals(nodeId))
        .findFirst()
        .orElseThrow();
  }

  private static Edge findEdge(Workflow workflow, String edgeId) {
    return workflow.edges().stream()
        .filter(edge -> edge.id().equals(edgeId))
        .findFirst()
        .orElseThrow();
  }

  private static Map<String, String> nullableGroupMap(
      String firstNodeId, String firstGroupId, String secondNodeId, String secondGroupId) {
    Map<String, String> groups = new LinkedHashMap<>();
    groups.put(firstNodeId, firstGroupId);
    groups.put(secondNodeId, secondGroupId);
    return groups;
  }
}
