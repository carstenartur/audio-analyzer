package org.hammer.audio.workflow.editor.glsp;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.editor.WorkflowEditorService;
import org.hammer.audio.workflow.editor.WorkflowProjection;

/**
 * Executable protocol-level GLSP spike adapter for issue #219.
 *
 * <p>The adapter deliberately does not own workflow state. It derives a small GModel-shaped
 * projection from {@link WorkflowProjection}, translates GLSP-style actions into semantic {@link
 * WorkflowOperation}s and delegates validation and mutation to {@link WorkflowEditorService}.
 */
public final class GlspWorkflowAdapter {

  private final WorkflowEditorService editorService;

  public GlspWorkflowAdapter(WorkflowEditorService editorService) {
    this.editorService = Objects.requireNonNull(editorService, "editorService");
  }

  /** Returns the current server-authoritative graph as a GLSP-shaped immutable projection. */
  public GGraph currentGraph() {
    return toGGraph(editorService.currentProjection());
  }

  /** Applies one GLSP-style action and returns the rebuilt graph projection. */
  public GGraph apply(Action action) {
    Objects.requireNonNull(action, "action");
    WorkflowProjection current = editorService.currentProjection();
    WorkflowOperation operation = toWorkflowOperation(action, current);
    return toGGraph(editorService.applyOperation(operation));
  }

  private static WorkflowOperation toWorkflowOperation(
      Action action, WorkflowProjection currentProjection) {
    return switch (action) {
      case CreateEdgeAction create ->
          new WorkflowOperation.ConnectPorts(
              create.operationId(),
              create.timestamp(),
              create.author(),
              new Edge(
                  create.edgeId(),
                  create.sourceNodeId(),
                  create.sourcePortId(),
                  create.targetNodeId(),
                  create.targetPortId()));
      case DeleteEdgeAction delete -> {
        WorkflowProjection.EdgeProjection edge =
            currentProjection.edges().stream()
                .filter(candidate -> candidate.id().equals(delete.edgeId()))
                .findFirst()
                .orElseThrow(
                    () -> new IllegalArgumentException("GLSP edge not found: " + delete.edgeId()));
        Edge snapshot =
            new Edge(
                edge.id(), edge.source(), edge.sourceHandle(), edge.target(), edge.targetHandle());
        yield new WorkflowOperation.DisconnectPorts(
            delete.operationId(), delete.timestamp(), delete.author(), delete.edgeId(), snapshot);
      }
      case ChangePropertyAction change -> {
        WorkflowProjection.NodeProjection node =
            currentProjection.nodes().stream()
                .filter(candidate -> candidate.id().equals(change.nodeId()))
                .findFirst()
                .orElseThrow(
                    () -> new IllegalArgumentException("GLSP node not found: " + change.nodeId()));
        yield new WorkflowOperation.UpdateProperty(
            change.operationId(),
            change.timestamp(),
            change.author(),
            WorkflowOperation.PropertyTarget.NODE,
            change.nodeId(),
            change.propertyKey(),
            node.properties().get(change.propertyKey()),
            change.newValue());
      }
    };
  }

  private static GGraph toGGraph(WorkflowProjection projection) {
    List<GNode> nodes =
        projection.nodes().stream()
            .map(
                node ->
                    new GNode(
                        node.id(),
                        node.label(),
                        node.type(),
                        node.inputHandles().stream()
                            .map(
                                port ->
                                    new GPort(
                                        node.id() + "::" + port.id(),
                                        port.id(),
                                        port.name(),
                                        port.dataType(),
                                        PortKind.INPUT))
                            .toList(),
                        node.outputHandles().stream()
                            .map(
                                port ->
                                    new GPort(
                                        node.id() + "::" + port.id(),
                                        port.id(),
                                        port.name(),
                                        port.dataType(),
                                        PortKind.OUTPUT))
                            .toList(),
                        node.properties()))
            .toList();
    List<GEdge> edges =
        projection.edges().stream()
            .map(
                edge ->
                    new GEdge(
                        edge.id(),
                        edge.source() + "::" + edge.sourceHandle(),
                        edge.target() + "::" + edge.targetHandle()))
            .toList();
    return new GGraph(projection.workflowId(), projection.workflowName(), nodes, edges);
  }

  public sealed interface Action permits CreateEdgeAction, DeleteEdgeAction, ChangePropertyAction {
    String operationId();

    Instant timestamp();

    String author();
  }

  public record CreateEdgeAction(
      String operationId,
      Instant timestamp,
      String author,
      String edgeId,
      String sourceNodeId,
      String sourcePortId,
      String targetNodeId,
      String targetPortId)
      implements Action {}

  public record DeleteEdgeAction(
      String operationId, Instant timestamp, String author, String edgeId)
      implements Action {}

  public record ChangePropertyAction(
      String operationId,
      Instant timestamp,
      String author,
      String nodeId,
      String propertyKey,
      String newValue)
      implements Action {}

  public record GGraph(String id, String label, List<GNode> nodes, List<GEdge> edges) {
    public GGraph {
      nodes = List.copyOf(nodes);
      edges = List.copyOf(edges);
    }
  }

  public record GNode(
      String id,
      String label,
      String type,
      List<GPort> inputPorts,
      List<GPort> outputPorts,
      Map<String, String> properties) {
    public GNode {
      inputPorts = List.copyOf(inputPorts);
      outputPorts = List.copyOf(outputPorts);
      properties = Map.copyOf(properties);
    }
  }

  public record GPort(
      String id, String semanticPortId, String label, String dataType, PortKind kind) {}

  public record GEdge(String id, String sourcePortId, String targetPortId) {}

  public enum PortKind {
    INPUT,
    OUTPUT
  }
}
