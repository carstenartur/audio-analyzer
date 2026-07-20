package org.hammer.audio.dsp.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.catalog.ExperimentNodeProtocol;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Input;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Violation;

/** Performs deterministic-backend capability, parameter and linear-topology preflight. */
final class DeterministicAudioWorkflowValidator {

  private final DeterministicAudioNodeExecutorRegistry executorRegistry;

  DeterministicAudioWorkflowValidator(DeterministicAudioNodeExecutorRegistry executorRegistry) {
    this.executorRegistry = Objects.requireNonNull(executorRegistry, "executorRegistry");
  }

  List<Violation> validate(Input input) {
    Objects.requireNonNull(input, "input");
    List<Violation> violations = new ArrayList<>();
    Map<String, Node> nodes = indexNodes(input);
    Map<String, List<Edge>> incoming = groupEdges(input, false);
    Map<String, List<Edge>> outgoing = groupEdges(input, true);
    int generatorCount = 0;
    int gainCount = 0;
    for (Node node : input.snapshot().nodes()) {
      if (ExperimentNodeProtocol.TYPE_SYNTHETIC_SIGNAL_GENERATOR.equals(node.type())) {
        generatorCount++;
      }
      if (ExperimentNodeProtocol.TYPE_GAIN.equals(node.type())) {
        gainCount++;
      }
      executorRegistry
          .find(node.type())
          .ifPresentOrElse(
              executor ->
                  violations.addAll(
                      executor.validate(node, incoming.getOrDefault(node.id(), List.of()))),
              () -> addUnsupportedNode(node, violations));
    }
    validateNodeCounts(generatorCount, gainCount, violations);
    validateLinearEdgeCount(input, violations);
    validateEdges(input.snapshot().edges(), nodes, violations);
    validateTerminalNode(nodes, outgoing, violations);
    return List.copyOf(violations);
  }

  static String format(List<Violation> violations) {
    return violations.stream()
        .map(violation -> violation.code() + ": " + violation.message())
        .collect(Collectors.joining("; "));
  }

  private static void addUnsupportedNode(Node node, List<Violation> violations) {
    violations.add(
        new Violation(
            DeterministicAudioDiagnostics.UNSUPPORTED_NODE,
            "No deterministic audio executor is registered for node type '" + node.type() + "'",
            node.id()));
  }

  private static void validateNodeCounts(
      int generatorCount, int gainCount, List<Violation> violations) {
    if (generatorCount != 1) {
      violations.add(
          new Violation(
              DeterministicAudioDiagnostics.INVALID_TOPOLOGY,
              "Deterministic audio workflows require exactly one synthetic signal generator, found "
                  + generatorCount,
              null));
    }
    if (gainCount < 1) {
      violations.add(
          new Violation(
              DeterministicAudioDiagnostics.INVALID_TOPOLOGY,
              "Deterministic audio workflows require at least one gain node",
              null));
    }
  }

  private static void validateLinearEdgeCount(Input input, List<Violation> violations) {
    int expectedEdges = Math.max(0, input.snapshot().nodes().size() - 1);
    if (input.snapshot().edges().size() != expectedEdges) {
      violations.add(
          new Violation(
              DeterministicAudioDiagnostics.INVALID_TOPOLOGY,
              "Deterministic audio workflows must form one linear chain with "
                  + expectedEdges
                  + " edge(s), found "
                  + input.snapshot().edges().size(),
              null));
    }
  }

  private static void validateEdges(
      List<Edge> edges, Map<String, Node> nodes, List<Violation> violations) {
    for (Edge edge : edges) {
      Node source = nodes.get(edge.sourceNodeId());
      Node target = nodes.get(edge.targetNodeId());
      if (source == null || target == null) {
        violations.add(
            new Violation(
                DeterministicAudioDiagnostics.INVALID_TOPOLOGY,
                "Edge '" + edge.id() + "' references an unknown node",
                null));
        continue;
      }
      validateSourcePort(edge, source, violations);
      validateTargetPort(edge, target, violations);
    }
  }

  private static void validateSourcePort(Edge edge, Node source, List<Violation> violations) {
    String expectedSourcePort =
        ExperimentNodeProtocol.TYPE_SYNTHETIC_SIGNAL_GENERATOR.equals(source.type())
            ? ExperimentNodeProtocol.SIGNAL_OUTPUT_PORT
            : ExperimentNodeProtocol.AUDIO_OUTPUT_PORT;
    if (!expectedSourcePort.equals(edge.sourcePortId())) {
      violations.add(
          new Violation(
              DeterministicAudioDiagnostics.INVALID_TOPOLOGY,
              "Edge '" + edge.id() + "' must use source port '" + expectedSourcePort + "'",
              source.id()));
    }
  }

  private static void validateTargetPort(Edge edge, Node target, List<Violation> violations) {
    if (!ExperimentNodeProtocol.TYPE_GAIN.equals(target.type())
        || !ExperimentNodeProtocol.AUDIO_INPUT_PORT.equals(edge.targetPortId())) {
      violations.add(
          new Violation(
              DeterministicAudioDiagnostics.INVALID_TOPOLOGY,
              "Edge '" + edge.id() + "' must target a gain node's audio input",
              target.id()));
    }
  }

  private static void validateTerminalNode(
      Map<String, Node> nodes, Map<String, List<Edge>> outgoing, List<Violation> violations) {
    List<Node> terminalNodes =
        nodes.values().stream()
            .filter(node -> outgoing.getOrDefault(node.id(), List.of()).isEmpty())
            .toList();
    if (terminalNodes.size() != 1) {
      violations.add(
          new Violation(
              DeterministicAudioDiagnostics.INVALID_TOPOLOGY,
              "Deterministic audio workflows require exactly one terminal node, found "
                  + terminalNodes.size(),
              null));
      return;
    }
    Node terminal = terminalNodes.getFirst();
    if (!ExperimentNodeProtocol.TYPE_GAIN.equals(terminal.type())) {
      violations.add(
          new Violation(
              DeterministicAudioDiagnostics.INVALID_TOPOLOGY,
              "The terminal deterministic audio node must be a gain node",
              terminal.id()));
    }
  }

  private static Map<String, Node> indexNodes(Input input) {
    Map<String, Node> nodes = new ConcurrentHashMap<>();
    for (Node node : input.snapshot().nodes()) {
      nodes.put(node.id(), node);
    }
    return Map.copyOf(nodes);
  }

  private static Map<String, List<Edge>> groupEdges(Input input, boolean bySource) {
    Map<String, List<Edge>> grouped = new ConcurrentHashMap<>();
    for (Edge edge : input.snapshot().edges()) {
      String key = bySource ? edge.sourceNodeId() : edge.targetNodeId();
      grouped.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(edge);
    }
    return Map.copyOf(grouped);
  }
}
