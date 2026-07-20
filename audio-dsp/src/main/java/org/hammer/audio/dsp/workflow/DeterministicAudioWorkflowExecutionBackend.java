package org.hammer.audio.dsp.workflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.hammer.audio.dsp.GainProcessor;
import org.hammer.audio.dsp.workflow.DeterministicAudioParameters.Gain;
import org.hammer.audio.dsp.workflow.DeterministicAudioParameters.SyntheticSignal;
import org.hammer.audio.signal.SineGenerator;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.catalog.ExperimentNodeCatalog;
import org.hammer.audio.workflow.execution.ExecutionContext;
import org.hammer.audio.workflow.execution.ExecutionStatus;
import org.hammer.audio.workflow.execution.ReproducibilityBundle;
import org.hammer.audio.workflow.execution.WorkflowExecutionBackendException;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Control;
import org.hammer.audio.workflow.execution.WorkflowRunModels.ExecutionBackend;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Input;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Mode;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Result;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Violation;

/** Executes deterministic synthetic-signal and gain workflows on immutable {@link AudioBlock}s. */
public final class DeterministicAudioWorkflowExecutionBackend implements ExecutionBackend {

  private static final String UNSUPPORTED_NODE = "UNSUPPORTED_NODE";
  private static final String INVALID_PARAMETER = "INVALID_PARAMETER";
  private static final String INVALID_TOPOLOGY = "INVALID_TOPOLOGY";
  private static final int SOURCE_SAMPLE_SIZE_BITS = 32;

  @Override
  public Mode mode() {
    return Mode.COMPUTATION;
  }

  @Override
  public List<Violation> validate(Input input) {
    Objects.requireNonNull(input, "input");
    List<Violation> violations = new ArrayList<>();
    Map<String, Node> nodes = indexNodes(input);
    Map<String, List<Edge>> incoming = incomingEdges(input);
    Map<String, List<Edge>> outgoing = outgoingEdges(input);
    int generatorCount = 0;
    int gainCount = 0;
    for (Node node : input.snapshot().nodes()) {
      switch (node.type()) {
        case ExperimentNodeCatalog.TYPE_SYNTHETIC_SIGNAL_GENERATOR -> {
          generatorCount++;
          validateSyntheticSignal(node, incoming.getOrDefault(node.id(), List.of()), violations);
        }
        case ExperimentNodeCatalog.TYPE_GAIN -> {
          gainCount++;
          validateGain(node, incoming.getOrDefault(node.id(), List.of()), violations);
        }
        default ->
            violations.add(
                new Violation(
                    UNSUPPORTED_NODE,
                    "No deterministic audio executor is registered for node type '"
                        + node.type()
                        + "'",
                    node.id()));
      }
    }
    validateNodeCounts(generatorCount, gainCount, violations);
    validateEdges(input.snapshot().edges(), nodes, violations);
    validateTerminalNode(nodes, outgoing, violations);
    return List.copyOf(violations);
  }

  @Override
  public Result execute(Input input, Control control) throws WorkflowExecutionBackendException {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(control, "control");
    List<Violation> violations = validate(input);
    if (!violations.isEmpty()) {
      throw new WorkflowExecutionBackendException(
          "Deterministic audio preflight rejected " + violations.size() + " violation(s)",
          new IllegalArgumentException(formatViolations(violations)));
    }
    try {
      return executeValidated(input, control);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      throw new WorkflowExecutionBackendException(
          "Deterministic audio execution failed: " + exception.getMessage(), exception);
    }
  }

  private Result executeValidated(Input input, Control control) {
    Map<String, Node> nodes = indexNodes(input);
    Map<String, Edge> incoming = singleIncomingEdge(input);
    Map<String, AudioBlock> outputs = new HashMap<>();
    ExecutionContext context =
        new ExecutionContext(input.runId() + ":audio-execution", input.plan(), input.capturedAt());
    List<String> orderedNodeIds = input.plan().orderedNodeIds();
    for (int index = 0; index < orderedNodeIds.size(); index++) {
      String nodeId = orderedNodeIds.get(index);
      if (control.cancellationRequested()) {
        cancelRemaining(context, orderedNodeIds, index);
        return cancellationResult(input, context, nodeId);
      }
      Node node = nodes.get(nodeId);
      context.updateNodeStatus(nodeId, ExecutionStatus.RUNNING);
      AudioBlock output = executeNode(node, incoming.get(nodeId), outputs);
      outputs.put(nodeId, output);
      context.updateNodeStatus(nodeId, ExecutionStatus.COMPLETED);
      control.progress(
          Math.multiplyExact(index + 1, 100) / orderedNodeIds.size(),
          "Computed " + nodeId);
    }
    String terminalNodeId = terminalNodeId(input);
    AudioBlock terminalOutput = outputs.get(terminalNodeId);
    Map<String, String> artifacts = new LinkedHashMap<>();
    artifacts.put(DeterministicAudioArtifacts.BACKEND_MODE, Mode.COMPUTATION.name());
    artifacts.putAll(
        AudioBlockEvidence.artifacts(
            terminalOutput, terminalNodeId, ExperimentNodeCatalog.AUDIO_OUTPUT_PORT));
    return result(input, context, artifacts);
  }

  private static AudioBlock executeNode(
      Node node, Edge incoming, Map<String, AudioBlock> outputs) {
    return switch (node.type()) {
      case ExperimentNodeCatalog.TYPE_SYNTHETIC_SIGNAL_GENERATOR -> generate(node);
      case ExperimentNodeCatalog.TYPE_GAIN -> {
        AudioBlock source = outputs.get(incoming.sourceNodeId());
        if (source == null) {
          throw new IllegalStateException(
              "Source node '" + incoming.sourceNodeId() + "' has no computed audio block");
        }
        Gain gain = DeterministicAudioParameters.parseGain(node);
        yield new GainProcessor(gain.factor()).process(source);
      }
      default -> throw new IllegalStateException("Unsupported node passed preflight: " + node.type());
    };
  }

  private static AudioBlock generate(Node node) {
    SyntheticSignal parameters = DeterministicAudioParameters.parseSyntheticSignal(node);
    AudioFormatDescriptor format =
        new AudioFormatDescriptor(
            parameters.sampleRateHz(), parameters.channels(), SOURCE_SAMPLE_SIZE_BITS);
    SineGenerator generator =
        new SineGenerator(format, parameters.frequencyHz(), parameters.amplitude());
    AudioBlock generated = generator.nextBlock(parameters.frameCount());
    return AudioBlock.wrap(format, generated.samples(), 0L, 0L);
  }

  private static Result cancellationResult(
      Input input, ExecutionContext context, String cancelledAtNode) {
    Map<String, String> artifacts =
        Map.of(
            DeterministicAudioArtifacts.BACKEND_MODE,
            Mode.COMPUTATION.name(),
            DeterministicAudioArtifacts.CANCELLED_AT_NODE,
            cancelledAtNode);
    return result(input, context, artifacts);
  }

  private static Result result(
      Input input, ExecutionContext context, Map<String, String> artifacts) {
    Instant completedAt = input.capturedAt();
    ReproducibilityBundle bundle =
        new ReproducibilityBundle(
            input.snapshot(), context.toResult(completedAt), input.commitId(), null);
    return new Result(bundle, artifacts);
  }

  private static void validateSyntheticSignal(
      Node node, List<Edge> incoming, List<Violation> violations) {
    if (!incoming.isEmpty()) {
      violations.add(
          new Violation(
              INVALID_TOPOLOGY,
              "Synthetic signal generators must not have incoming edges",
              node.id()));
    }
    try {
      DeterministicAudioParameters.parseSyntheticSignal(node);
    } catch (IllegalArgumentException exception) {
      violations.add(new Violation(INVALID_PARAMETER, exception.getMessage(), node.id()));
    }
  }

  private static void validateGain(
      Node node, List<Edge> incoming, List<Violation> violations) {
    if (incoming.size() != 1) {
      violations.add(
          new Violation(
              INVALID_TOPOLOGY,
              "Gain nodes require exactly one incoming audio edge, found " + incoming.size(),
              node.id()));
    }
    try {
      DeterministicAudioParameters.parseGain(node);
    } catch (IllegalArgumentException exception) {
      violations.add(new Violation(INVALID_PARAMETER, exception.getMessage(), node.id()));
    }
  }

  private static void validateNodeCounts(
      int generatorCount, int gainCount, List<Violation> violations) {
    if (generatorCount != 1) {
      violations.add(
          new Violation(
              INVALID_TOPOLOGY,
              "Deterministic audio workflows require exactly one synthetic signal generator, found "
                  + generatorCount,
              null));
    }
    if (gainCount < 1) {
      violations.add(
          new Violation(
              INVALID_TOPOLOGY,
              "Deterministic audio workflows require at least one gain node",
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
                INVALID_TOPOLOGY,
                "Edge '" + edge.id() + "' references an unknown node",
                null));
        continue;
      }
      String expectedSourcePort =
          ExperimentNodeCatalog.TYPE_SYNTHETIC_SIGNAL_GENERATOR.equals(source.type())
              ? ExperimentNodeCatalog.SIGNAL_OUTPUT_PORT
              : ExperimentNodeCatalog.AUDIO_OUTPUT_PORT;
      if (!expectedSourcePort.equals(edge.sourcePortId())) {
        violations.add(
            new Violation(
                INVALID_TOPOLOGY,
                "Edge '"
                    + edge.id()
                    + "' must use source port '"
                    + expectedSourcePort
                    + "'",
                source.id()));
      }
      if (!ExperimentNodeCatalog.TYPE_GAIN.equals(target.type())
          || !ExperimentNodeCatalog.AUDIO_INPUT_PORT.equals(edge.targetPortId())) {
        violations.add(
            new Violation(
                INVALID_TOPOLOGY,
                "Edge '" + edge.id() + "' must target a gain node's audio input",
                target.id()));
      }
    }
  }

  private static void validateTerminalNode(
      Map<String, Node> nodes,
      Map<String, List<Edge>> outgoing,
      List<Violation> violations) {
    List<Node> terminalNodes =
        nodes.values().stream()
            .filter(node -> outgoing.getOrDefault(node.id(), List.of()).isEmpty())
            .toList();
    if (terminalNodes.size() != 1) {
      violations.add(
          new Violation(
              INVALID_TOPOLOGY,
              "Deterministic audio workflows require exactly one terminal node, found "
                  + terminalNodes.size(),
              null));
      return;
    }
    Node terminal = terminalNodes.getFirst();
    if (!ExperimentNodeCatalog.TYPE_GAIN.equals(terminal.type())) {
      violations.add(
          new Violation(
              INVALID_TOPOLOGY,
              "The terminal deterministic audio node must be a gain node",
              terminal.id()));
    }
  }

  private static Map<String, Node> indexNodes(Input input) {
    return input.snapshot().nodes().stream()
        .collect(Collectors.toUnmodifiableMap(Node::id, Function.identity()));
  }

  private static Map<String, List<Edge>> incomingEdges(Input input) {
    return input.snapshot().edges().stream()
        .collect(Collectors.groupingBy(Edge::targetNodeId));
  }

  private static Map<String, List<Edge>> outgoingEdges(Input input) {
    return input.snapshot().edges().stream()
        .collect(Collectors.groupingBy(Edge::sourceNodeId));
  }

  private static Map<String, Edge> singleIncomingEdge(Input input) {
    return input.snapshot().edges().stream()
        .collect(Collectors.toUnmodifiableMap(Edge::targetNodeId, Function.identity()));
  }

  private static String terminalNodeId(Input input) {
    Map<String, List<Edge>> outgoing = outgoingEdges(input);
    return input.snapshot().nodes().stream()
        .filter(node -> outgoing.getOrDefault(node.id(), List.of()).isEmpty())
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Validated workflow has no terminal node"))
        .id();
  }

  private static void cancelRemaining(
      ExecutionContext context, List<String> orderedNodeIds, int firstRemainingIndex) {
    for (int index = firstRemainingIndex; index < orderedNodeIds.size(); index++) {
      context.updateNodeStatus(orderedNodeIds.get(index), ExecutionStatus.CANCELLED);
    }
  }

  private static String formatViolations(List<Violation> violations) {
    return violations.stream()
        .map(violation -> violation.code() + ": " + violation.message())
        .collect(Collectors.joining("; "));
  }
}
