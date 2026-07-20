package org.hammer.audio.dsp.workflow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.catalog.ExperimentNodeCatalog;
import org.hammer.audio.workflow.catalog.ExperimentNodeParameters;
import org.hammer.audio.workflow.catalog.ExperimentNodeProtocol;
import org.hammer.audio.workflow.execution.ExecutionPlan;
import org.hammer.audio.workflow.execution.ExecutionSnapshot;
import org.hammer.audio.workflow.execution.ExecutionStatus;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Control;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Input;
import org.hammer.audio.workflow.execution.WorkflowRunModels.LiveSessionSource;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Result;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Violation;
import org.junit.jupiter.api.Test;

class DeterministicAudioWorkflowExecutionBackendTest {

  private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");

  @Test
  void computesNumericallySpecifiedSyntheticSignalAndGain() throws Exception {
    Input input = input(linearWorkflow(8, 2.0f));
    DeterministicAudioWorkflowExecutionBackend backend =
        new DeterministicAudioWorkflowExecutionBackend();

    Result result = backend.execute(input, neverCancelled());

    assertEquals(ExecutionStatus.COMPLETED, result.reproducibilityBundle().result().overallStatus());
    assertEquals(
        DeterministicAudioWorkflowExecutionBackend.BACKEND_VERSION,
        result.artifacts().get(DeterministicAudioArtifacts.BACKEND_VERSION));
    assertEquals(64, result.artifacts().get(DeterministicAudioArtifacts.OUTPUT_DIGEST_SHA256).length());
    float[] expected = {
      0.0f,
      (float) Math.sqrt(0.5d),
      1.0f,
      (float) Math.sqrt(0.5d),
      0.0f,
      (float) -Math.sqrt(0.5d),
      -1.0f,
      (float) -Math.sqrt(0.5d)
    };
    assertArrayEquals(expected, preview(result), 1.0e-6f);
  }

  @Test
  void repeatedByteIdenticalInputProducesIdenticalContentDigest() throws Exception {
    Input input = input(linearWorkflow(16_384, 0.75f));
    DeterministicAudioWorkflowExecutionBackend backend =
        new DeterministicAudioWorkflowExecutionBackend();

    Result first = backend.execute(input, neverCancelled());
    Result second = backend.execute(input, neverCancelled());

    assertEquals(
        first.artifacts().get(DeterministicAudioArtifacts.OUTPUT_DIGEST_SHA256),
        second.artifacts().get(DeterministicAudioArtifacts.OUTPUT_DIGEST_SHA256));
    assertEquals(
        first.artifacts().get(DeterministicAudioArtifacts.OUTPUT_RMS),
        second.artifacts().get(DeterministicAudioArtifacts.OUTPUT_RMS));
  }

  @Test
  void cancellationStopsInsideBoundedSignalChunks() throws Exception {
    Input input = input(linearWorkflow(20_000, 1.0f));
    AtomicInteger checks = new AtomicInteger();
    Control cancelDuringGenerator =
        new Control() {
          @Override
          public boolean cancellationRequested() {
            return checks.incrementAndGet() >= 3;
          }

          @Override
          public void progress(int percentage, String message) {
            // Progress is not relevant for this cancellation contract.
          }
        };

    Result result =
        new DeterministicAudioWorkflowExecutionBackend().execute(input, cancelDuringGenerator);

    assertEquals(ExecutionStatus.CANCELLED, result.reproducibilityBundle().result().overallStatus());
    assertEquals(
        ExecutionStatus.CANCELLED,
        result.reproducibilityBundle().result().nodeStatuses().get("node.generator"));
    assertEquals(
        ExecutionStatus.CANCELLED,
        result.reproducibilityBundle().result().nodeStatuses().get("node.gain.1"));
    assertEquals(
        "node.generator", result.artifacts().get(DeterministicAudioArtifacts.CANCELLED_AT_NODE));
  }

  @Test
  void nodeFailureSkipsDownstreamNodesAndRetainsProvenance() throws Exception {
    Workflow workflow = linearWorkflowWithTwoGains();
    DeterministicAudioNodeExecutor failingGain =
        new DeterministicAudioNodeExecutor() {
          @Override
          public String nodeType() {
            return ExperimentNodeProtocol.TYPE_GAIN;
          }

          @Override
          public List<Violation> validate(Node node, List<Edge> incomingEdges) {
            return List.of();
          }

          @Override
          public AudioBlock execute(Node node, AudioBlock input, Control control) {
            throw new IllegalStateException("Deliberate gain failure");
          }
        };
    DeterministicAudioNodeExecutorRegistry registry =
        new DeterministicAudioNodeExecutorRegistry(
            List.of(new SyntheticSignalNodeExecutor(), failingGain));
    DeterministicAudioWorkflowExecutionBackend backend =
        new DeterministicAudioWorkflowExecutionBackend(registry);

    Result result = backend.execute(input(workflow), neverCancelled());

    assertEquals(ExecutionStatus.FAILED, result.reproducibilityBundle().result().overallStatus());
    assertEquals(
        ExecutionStatus.COMPLETED,
        result.reproducibilityBundle().result().nodeStatuses().get("node.generator"));
    assertEquals(
        ExecutionStatus.FAILED,
        result.reproducibilityBundle().result().nodeStatuses().get("node.gain.1"));
    assertEquals(
        ExecutionStatus.SKIPPED,
        result.reproducibilityBundle().result().nodeStatuses().get("node.gain.2"));
    assertEquals(
        "node.gain.1", result.artifacts().get(DeterministicAudioArtifacts.FAILED_NODE_ID));
    assertEquals(
        ExperimentNodeProtocol.TYPE_GAIN,
        result.artifacts().get(DeterministicAudioArtifacts.FAILED_NODE_TYPE));
    assertEquals(
        "node.gain.2", result.artifacts().get(DeterministicAudioArtifacts.SKIPPED_NODE_IDS));
    assertTrue(
        result.artifacts().get(DeterministicAudioArtifacts.FAILURE_MESSAGE).contains("Deliberate"));
  }

  @Test
  void unsupportedAndInvalidNodesFailPreflightWithNodeSpecificViolations() {
    Node unsupported =
        new Node("node.unsupported", "fft", "Unsupported", List.of(), List.of());
    Input unsupportedInput =
        input(new Workflow("workflow.unsupported", "Unsupported", List.of(unsupported), List.of()));
    Node invalidGenerator = configuredGenerator(8);
    invalidGenerator =
        withMetadata(
            invalidGenerator,
            Map.of(
                ExperimentNodeParameters.SIGNAL_WAVEFORM,
                ExperimentNodeParameters.WAVEFORM_SINE));
    Input invalidInput =
        input(new Workflow("workflow.invalid", "Invalid", List.of(invalidGenerator), List.of()));
    DeterministicAudioWorkflowExecutionBackend backend =
        new DeterministicAudioWorkflowExecutionBackend();

    List<Violation> unsupportedViolations = backend.validate(unsupportedInput);
    List<Violation> invalidViolations = backend.validate(invalidInput);

    assertTrue(
        unsupportedViolations.stream()
            .anyMatch(
                violation ->
                    DeterministicAudioDiagnostics.UNSUPPORTED_NODE.equals(violation.code())
                        && "node.unsupported".equals(violation.nodeId())));
    assertTrue(
        invalidViolations.stream()
            .anyMatch(
                violation ->
                    DeterministicAudioDiagnostics.INVALID_PARAMETER.equals(violation.code())
                        && "node.generator".equals(violation.nodeId())));
  }

  private static Workflow linearWorkflow(int frames, float gainFactor) {
    Node generator = configuredGenerator(frames);
    Node gain = configuredGain("node.gain.1", gainFactor);
    Edge edge =
        new Edge(
            "edge.generator-gain",
            generator.id(),
            ExperimentNodeProtocol.SIGNAL_OUTPUT_PORT,
            gain.id(),
            ExperimentNodeProtocol.AUDIO_INPUT_PORT);
    return new Workflow(
        "workflow.test", "Deterministic audio", List.of(generator, gain), List.of(edge));
  }

  private static Workflow linearWorkflowWithTwoGains() {
    Node generator = configuredGenerator(8);
    Node firstGain = configuredGain("node.gain.1", 1.0f);
    Node secondGain = configuredGain("node.gain.2", 1.0f);
    Edge first =
        new Edge(
            "edge.generator-gain1",
            generator.id(),
            ExperimentNodeProtocol.SIGNAL_OUTPUT_PORT,
            firstGain.id(),
            ExperimentNodeProtocol.AUDIO_INPUT_PORT);
    Edge second =
        new Edge(
            "edge.gain1-gain2",
            firstGain.id(),
            ExperimentNodeProtocol.AUDIO_OUTPUT_PORT,
            secondGain.id(),
            ExperimentNodeProtocol.AUDIO_INPUT_PORT);
    return new Workflow(
        "workflow.failure",
        "Failure provenance",
        List.of(generator, firstGain, secondGain),
        List.of(first, second));
  }

  private static Node configuredGenerator(int frames) {
    return withMetadata(
        ExperimentNodeCatalog.syntheticSignalGenerator("node.generator"),
        Map.of(
            ExperimentNodeParameters.SIGNAL_WAVEFORM,
            ExperimentNodeParameters.WAVEFORM_SINE,
            ExperimentNodeParameters.SIGNAL_FREQUENCY_HZ,
            "1000",
            ExperimentNodeParameters.SIGNAL_PHASE_RADIANS,
            "0",
            ExperimentNodeParameters.SIGNAL_AMPLITUDE,
            "0.5",
            ExperimentNodeParameters.SIGNAL_SAMPLE_RATE_HZ,
            "8000",
            ExperimentNodeParameters.SIGNAL_CHANNELS,
            "1",
            ExperimentNodeParameters.SIGNAL_FRAME_COUNT,
            Integer.toString(frames)));
  }

  private static Node configuredGain(String nodeId, float factor) {
    return withMetadata(
        ExperimentNodeCatalog.gain(nodeId),
        Map.of(ExperimentNodeParameters.GAIN_FACTOR, Float.toString(factor)));
  }

  private static Node withMetadata(Node node, Map<String, String> metadata) {
    return new Node(
        node.id(),
        node.type(),
        node.label(),
        node.inputPorts(),
        node.outputPorts(),
        new Metadata(metadata));
  }

  private static Input input(Workflow workflow) {
    ExecutionSnapshot snapshot = ExecutionSnapshot.of("snapshot.test", workflow, NOW);
    ExecutionPlan plan = ExecutionPlan.of("plan.test", snapshot);
    return new Input(
        "run.test",
        "command.test",
        new LiveSessionSource("session.test", 0),
        "canonical-dsl",
        "fingerprint",
        snapshot,
        plan,
        0L,
        null,
        NOW);
  }

  private static Control neverCancelled() {
    return new Control() {
      @Override
      public boolean cancellationRequested() {
        return false;
      }

      @Override
      public void progress(int percentage, String message) {
        // No-op test control.
      }
    };
  }

  private static float[] preview(Result result) {
    return java.util.Arrays.stream(
            result
                .artifacts()
                .get(DeterministicAudioArtifacts.OUTPUT_CHANNEL_ZERO_PREVIEW)
                .split(","))
        .mapToDouble(Float::parseFloat)
        .collect(
            () -> new FloatCollector(16),
            (collector, value) -> collector.add((float) value),
            FloatCollector::addAll)
        .toArray();
  }

  private static final class FloatCollector {
    private final float[] values;
    private int size;

    private FloatCollector(int capacity) {
      values = new float[capacity];
    }

    private void add(float value) {
      values[size++] = value;
    }

    private void addAll(FloatCollector other) {
      for (int index = 0; index < other.size; index++) {
        add(other.values[index]);
      }
    }

    private float[] toArray() {
      return java.util.Arrays.copyOf(values, size);
    }
  }
}
