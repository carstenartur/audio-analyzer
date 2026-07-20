package org.hammer.audio.dsp.workflow;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.catalog.ExperimentNodeProtocol;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Input;

/** Compiled immutable node and edge lookup for one validated deterministic workflow input. */
final class DeterministicAudioExecutionGraph {

  private final Map<String, Node> nodesById;
  private final Map<String, Edge> incomingByTargetNodeId;
  private final String terminalIdentity;

  private DeterministicAudioExecutionGraph(
      Map<String, Node> nodesById,
      Map<String, Edge> incomingByTargetNodeId,
      String terminalNodeId) {
    this.nodesById = Map.copyOf(nodesById);
    this.incomingByTargetNodeId = Map.copyOf(incomingByTargetNodeId);
    this.terminalIdentity = Objects.requireNonNull(terminalNodeId, "terminalNodeId");
  }

  static DeterministicAudioExecutionGraph from(Input input) {
    Objects.requireNonNull(input, "input");
    Map<String, Node> nodes = new ConcurrentHashMap<>();
    for (Node node : input.snapshot().nodes()) {
      nodes.put(node.id(), node);
    }
    Map<String, Edge> incoming = new ConcurrentHashMap<>();
    Set<String> sourceNodeIds = ConcurrentHashMap.newKeySet();
    for (Edge edge : input.snapshot().edges()) {
      incoming.put(edge.targetNodeId(), edge);
      sourceNodeIds.add(edge.sourceNodeId());
    }
    String terminal =
        input.snapshot().nodes().stream()
            .map(Node::id)
            .filter(nodeId -> !sourceNodeIds.contains(nodeId))
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("Validated workflow has no terminal node"));
    return new DeterministicAudioExecutionGraph(nodes, incoming, terminal);
  }

  Node node(String nodeId) {
    Node node = nodesById.get(nodeId);
    if (node == null) {
      throw new IllegalArgumentException("Unknown deterministic execution node: " + nodeId);
    }
    return node;
  }

  AudioBlock upstreamInput(String nodeId, Map<String, AudioBlock> outputs) {
    Edge incoming = incomingByTargetNodeId.get(nodeId);
    if (incoming == null) {
      return null;
    }
    AudioBlock source = outputs.get(incoming.sourceNodeId());
    if (source == null) {
      throw new IllegalStateException(
          "Source node '" + incoming.sourceNodeId() + "' has no computed audio block");
    }
    return source;
  }

  String terminalNodeId() {
    return terminalIdentity;
  }

  String terminalOutputPortId() {
    return ExperimentNodeProtocol.AUDIO_OUTPUT_PORT;
  }
}
