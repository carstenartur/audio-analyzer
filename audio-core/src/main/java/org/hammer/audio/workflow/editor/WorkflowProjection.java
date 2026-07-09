package org.hammer.audio.workflow.editor;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.workflow.Workflow;

/**
 * React Flow–ready read model derived from a {@link Workflow}.
 *
 * <p>Each {@link NodeProjection} carries typed-port handle descriptors ({@link HandleProjection})
 * so the React Flow client can render typed {@code Handle} components without parsing domain
 * objects directly. Each {@link EdgeProjection} maps directly to a React Flow edge.
 *
 * <p>Instances are immutable and created via {@link #fromWorkflow(Workflow)}.
 *
 * @param workflowId stable workflow identifier
 * @param workflowName human-readable workflow name
 * @param nodes projected nodes with typed-port handle lists
 * @param edges projected edges with source and target handle identifiers
 */
public record WorkflowProjection(
    String workflowId,
    String workflowName,
    List<NodeProjection> nodes,
    List<EdgeProjection> edges) {

  public WorkflowProjection {
    Objects.requireNonNull(workflowId, "workflowId");
    Objects.requireNonNull(workflowName, "workflowName");
    nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
    edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
  }

  /**
   * Projects a {@link Workflow} into the React Flow–ready read model.
   *
   * @param workflow source workflow
   * @return projection ready for serialisation to the browser
   */
  public static WorkflowProjection fromWorkflow(Workflow workflow) {
    Objects.requireNonNull(workflow, "workflow");
    List<NodeProjection> nodeProjections =
        workflow.nodes().stream()
            .map(
                node ->
                    new NodeProjection(
                        node.id(),
                        node.type(),
                        node.label(),
                        node.inputPorts().stream()
                            .map(p -> new HandleProjection(p.id(), p.name(), p.dataType().id()))
                            .toList(),
                        node.outputPorts().stream()
                            .map(p -> new HandleProjection(p.id(), p.name(), p.dataType().id()))
                            .toList(),
                        Map.copyOf(node.metadata().entries())))
            .toList();
    List<EdgeProjection> edgeProjections =
        workflow.edges().stream()
            .map(
                edge ->
                    new EdgeProjection(
                        edge.id(),
                        edge.sourceNodeId(),
                        edge.sourcePortId(),
                        edge.targetNodeId(),
                        edge.targetPortId()))
            .toList();
    return new WorkflowProjection(workflow.id(), workflow.name(), nodeProjections, edgeProjections);
  }

  /**
   * Projection of a single workflow node with typed-port handle lists and property values.
   *
   * @param id stable node identifier (maps to React Flow {@code node.id})
   * @param type node type string (maps to React Flow {@code node.type})
   * @param label human-readable label (maps to React Flow {@code node.data.label})
   * @param inputHandles typed input port descriptors (source of React Flow target {@code Handle}s)
   * @param outputHandles typed output port descriptors (source of React Flow source {@code
   *     Handle}s)
   * @param properties metadata properties set on the node (e.g. parameter values updated via {@code
   *     UpdateProperty})
   */
  public record NodeProjection(
      String id,
      String type,
      String label,
      List<HandleProjection> inputHandles,
      List<HandleProjection> outputHandles,
      Map<String, String> properties) {

    public NodeProjection {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(label, "label");
      inputHandles = List.copyOf(Objects.requireNonNull(inputHandles, "inputHandles"));
      outputHandles = List.copyOf(Objects.requireNonNull(outputHandles, "outputHandles"));
      properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
    }
  }

  /**
   * Typed port descriptor used by the React Flow client to render a {@code Handle} component.
   *
   * @param id stable port identifier (React Flow {@code Handle id})
   * @param name human-readable port name
   * @param dataType workflow data type identifier (used for visual type colouring and tooltip)
   */
  public record HandleProjection(String id, String name, String dataType) {

    public HandleProjection {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(dataType, "dataType");
    }
  }

  /**
   * Projection of a workflow edge.
   *
   * @param id stable edge identifier (maps to React Flow {@code edge.id})
   * @param source source node identifier (maps to React Flow {@code edge.source})
   * @param sourceHandle source port identifier (maps to React Flow {@code edge.sourceHandle})
   * @param target target node identifier (maps to React Flow {@code edge.target})
   * @param targetHandle target port identifier (maps to React Flow {@code edge.targetHandle})
   */
  public record EdgeProjection(
      String id, String source, String sourceHandle, String target, String targetHandle) {

    public EdgeProjection {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(sourceHandle, "sourceHandle");
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(targetHandle, "targetHandle");
    }
  }
}
