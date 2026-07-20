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
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.catalog.ExperimentNodeProtocol;
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

  /** Version of the executable semantics and emitted artifact contract. */
  public static final String BACKEND_VERSION = "deterministic-audio-v1";

  private final DeterministicAudioNodeExecutorRegistry executorRegistry;

  /** Creates the production backend with the standard synthetic-source and gain executors. */
  public DeterministicAudioWorkflowExecutionBackend() {
    this(DeterministicAudioNodeExecutorRegistry.standard());
  }

  DeterministicAudioWorkflowExecutionBackend(
      DeterministicAudioNodeExecutorRegistry executorRegistry) {
    this.executorRegistry = Objects.requireNonNull(executorRegistry, "executorRegistry");
  }

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
              () ->
                  violations.add(
                      new Violation(
                          DeterministicAudioDiagnostics.UNSUPPORTED_NODE,
                          "No deterministic audio executor is registered for node type '"
                              + node.type()
                              + "'",
                          node.id())));
    }
    validateNodeCounts(generatorCount, gainCount, violations);
    validateLinearEdgeCount(input, violations);
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
    return executeValidated(input, control);
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
      control.progress(progressBefore(index, orderedNodeIds.size()), "Starting " + nodeId);
      try {
        DeterministicAudioNodeExecutor executor =
            executorRegistry
                .find(node.type())
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "Validated node has no executor: " + node.type()));
        AudioBlock output = executor.execute(node, upstreamInput(incoming.get(nodeId), outputs), control);
        outputs.put(nodeId, output);
        context.updateNodeStatus(nodeId, ExecutionStatus.COMPLETED);
        control.progress(progressAfter(index, orderedNodeIds.size()), "Computed " + nodeId);
      } catch (DeterministicAudioCancellationException cancellation) {
        cancelRemaining(context, orderedNodeIds, index);
        return cancellationResult(input, context, nodeId);
      } catch (RuntimeException failure) {
        return failureResult(input, context, orderedNodeIds, index, node, failure);
      }
    }
    String terminalNodeId = terminalNodeId(input);
    AudioBlock terminalOutput = outputs.get(terminalNodeId);
    Map<String, String> artifacts = baseArtifacts();
    artifacts.putAll(
        AudioBlockEvidence.artifacts(
            terminalOutput, terminalNodeId, ExperimentNodeProtocol.AUDIO_OUTPUT_PORT));
    return result(input, context, artifacts);
  }

  private static AudioBlock upstreamInput(Edge incoming, Map<String, AudioBlock> outputs) {
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

  private static Result cancellationResult(
      Input input, ExecutionContext context, String cancelledAtNode) {
    Map<String, String> artifacts = baseArtifacts();
    artifacts.put(DeterministicAudioArtifacts.CANCELLED_AT_NODE, cancelledAtNode);
    return result(input, context, artifacts);
  }

  private static Result failureResult(
      Input input,
      ExecutionContext context,
      List<String> orderedNodeIds,
      int failedIndex,
      Node failedNode,
      RuntimeException failure) {
    context.updateNodeStatus(failedNode.id(), ExecutionStatus.FAILED);
    List<String> skippedNodeIds = new ArrayList<>();
    for (int index = failedIndex + 1; index < orderedNodeIds.size(); index++) {
      String skippedNodeId = orderedNodeIds.get(index);
      context.updateNodeStatus(skippedNodeId, ExecutionStatus.SKIPPED);
      skippedNodeIds.add(skippedNodeId);
    }
    Map<String, String> artifacts = baseArtifacts();
    artifacts.put(DeterministicAudioArtifacts.FAILED_NODE_ID, failedNode.id());
    artifacts.put(DeterministicAudioArtifacts.FAILED_NODE_TYPE, failedNode.type());
    artifacts.put(DeterministicAudioArtifacts.FAILURE_CLASS, failure.getClass().getName());
    artifacts.put(
        DeterministicAudioArtifacts.FAILURE_MESSAGE,
        failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
    artifacts.put(
        DeterministicAudioArtifacts.SKIPPED_NODE_IDS, String.join(",", skippedNodeIds));
    return result(input, context, artifacts);
  }

  private static Map<String, String> baseArtifacts() {
    Map<String, String> artifacts = new LinkedHashMap<>();
    artifacts.put(DeterministicAudioArtifacts.BACKEND_MODE, Mode.COMPUTATION.name());
    artifacts.put(DeterministicAudioArtifacts.BACKEND_VERSION, BACKEND_VERSION);
    return artifacts;
  }

  private static Result result(
      Input input, ExecutionContext context, Map<String, String> artifacts) {
    Instant completedAt = input.capturedAt();
    ReproducibilityBundle bundle =
        new ReproducibilityBundle(
            input.snapshot(), context.toResult(completedAt), input.commitId(), null);
    return new Result(bundle, artifacts);
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
      String expectedSourcePort =
          ExperimentNodeProtocol.TYPE_SYNTHETIC_SIGNAL_GENERATOR.equals(source.type())
              ? ExperimentNodeProtocol.SIGNAL_OUTPUT_PORT
              : ExperimentNodeProtocol.AUDIO_OUTPUT_PORT;
      if (!expectedSourcePort.equals(edge.sourcePortId())) {
        violations.add(
            new Violation(
                DeterministicAudioDiagnostics.INVALID_TOPOLOGY,
                "Edge '"
                    + edge.id()
                    + "' must use source port '"
                    + expectedSourcePort
                    + "'",
                source.id()));
      }
      if (!ExperimentNodeProtocol.TYPE_GAIN.equals(target.type())
          || !ExperimentNodeProtocol.AUDIO_INPUT_PORT.equals(edge.targetPortId())) {
        violations.add(
            new Violation(
                DeterministicAudioDiagnostics.INVALID_TOPOLOGY,
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

  private static int progressBefore(int index, int nodeCount) {
    return Math.multiplyExact(index, 100) / nodeCount;
  }

  private static int progressAfter(int index, int nodeCount) {
    return Math.multiplyExact(index + 1, 100) / nodeCount;
  }

  private static String formatViolations(List<Violation> violations) {
    return violations.stream()
        .map(violation -> violation.code() + ": " + violation.message())
        .collect(Collectors.joining("; "));
  }
}
