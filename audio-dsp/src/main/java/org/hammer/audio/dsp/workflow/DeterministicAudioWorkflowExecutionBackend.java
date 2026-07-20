package org.hammer.audio.dsp.workflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.workflow.Node;
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
  private final DeterministicAudioWorkflowValidator workflowValidator;

  /** Creates the production backend with the standard synthetic-source and gain executors. */
  public DeterministicAudioWorkflowExecutionBackend() {
    this(DeterministicAudioNodeExecutorRegistry.standard());
  }

  DeterministicAudioWorkflowExecutionBackend(
      DeterministicAudioNodeExecutorRegistry executorRegistry) {
    this.executorRegistry = Objects.requireNonNull(executorRegistry, "executorRegistry");
    this.workflowValidator = new DeterministicAudioWorkflowValidator(executorRegistry);
  }

  @Override
  public Mode mode() {
    return Mode.COMPUTATION;
  }

  @Override
  public List<Violation> validate(Input input) {
    return workflowValidator.validate(input);
  }

  @Override
  public Result execute(Input input, Control control) throws WorkflowExecutionBackendException {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(control, "control");
    List<Violation> violations = validate(input);
    if (!violations.isEmpty()) {
      throw new WorkflowExecutionBackendException(
          "Deterministic audio preflight rejected " + violations.size() + " violation(s)",
          new IllegalArgumentException(DeterministicAudioWorkflowValidator.format(violations)));
    }
    return executeValidated(input, control);
  }

  private Result executeValidated(Input input, Control control) {
    DeterministicAudioExecutionGraph graph = DeterministicAudioExecutionGraph.from(input);
    Map<String, AudioBlock> outputs = new ConcurrentHashMap<>();
    ExecutionContext context =
        new ExecutionContext(input.runId() + ":audio-execution", input.plan(), input.capturedAt());
    List<String> orderedNodeIds = input.plan().orderedNodeIds();
    for (int index = 0; index < orderedNodeIds.size(); index++) {
      String nodeId = orderedNodeIds.get(index);
      if (control.cancellationRequested()) {
        cancelRemaining(context, orderedNodeIds, index);
        return cancellationResult(input, context, nodeId);
      }
      Node node = graph.node(nodeId);
      context.updateNodeStatus(nodeId, ExecutionStatus.RUNNING);
      control.progress(progressBefore(index, orderedNodeIds.size()), "Starting " + nodeId);
      try {
        DeterministicAudioNodeExecutor executor = requiredExecutor(node);
        AudioBlock output = executor.execute(node, graph.upstreamInput(nodeId, outputs), control);
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
    String terminalNodeId = graph.terminalNodeId();
    AudioBlock terminalOutput = outputs.get(terminalNodeId);
    Map<String, String> artifacts = baseArtifacts();
    artifacts.putAll(
        AudioBlockEvidence.artifacts(
            terminalOutput, terminalNodeId, graph.terminalOutputPortId()));
    return result(input, context, artifacts);
  }

  private DeterministicAudioNodeExecutor requiredExecutor(Node node) {
    return executorRegistry
        .find(node.type())
        .orElseThrow(
            () -> new IllegalStateException("Validated node has no executor: " + node.type()));
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
    artifacts.put(DeterministicAudioArtifacts.SKIPPED_NODE_IDS, String.join(",", skippedNodeIds));
    return result(input, context, artifacts);
  }

  private static Map<String, String> baseArtifacts() {
    Map<String, String> artifacts = new ConcurrentHashMap<>();
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
}
