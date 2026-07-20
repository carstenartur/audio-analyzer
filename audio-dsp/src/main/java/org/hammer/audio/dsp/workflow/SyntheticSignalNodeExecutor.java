package org.hammer.audio.dsp.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.hammer.audio.dsp.workflow.DeterministicAudioParameters.SyntheticSignal;
import org.hammer.audio.signal.SineGenerator;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.catalog.ExperimentNodeProtocol;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Control;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Violation;

/** Executes one deterministic synthetic sine-wave source in bounded chunks. */
final class SyntheticSignalNodeExecutor implements DeterministicAudioNodeExecutor {

  private static final int SOURCE_SAMPLE_SIZE_BITS = 32;
  private static final int CHUNK_FRAMES = 4_096;

  @Override
  public String nodeType() {
    return ExperimentNodeProtocol.TYPE_SYNTHETIC_SIGNAL_GENERATOR;
  }

  @Override
  public List<Violation> validate(Node node, List<Edge> incomingEdges) {
    Objects.requireNonNull(node, "node");
    Objects.requireNonNull(incomingEdges, "incomingEdges");
    List<Violation> violations = new ArrayList<>();
    if (!incomingEdges.isEmpty()) {
      violations.add(
          new Violation(
              DeterministicAudioDiagnostics.INVALID_TOPOLOGY,
              "Synthetic signal generators must not have incoming edges",
              node.id()));
    }
    try {
      DeterministicAudioParameters.parseSyntheticSignal(node);
    } catch (IllegalArgumentException exception) {
      violations.add(
          new Violation(
              DeterministicAudioDiagnostics.INVALID_PARAMETER, exception.getMessage(), node.id()));
    }
    return List.copyOf(violations);
  }

  @Override
  public AudioBlock execute(Node node, AudioBlock input, Control control) {
    Objects.requireNonNull(node, "node");
    Objects.requireNonNull(control, "control");
    if (input != null) {
      throw new IllegalArgumentException("Synthetic signal sources must not receive audio input");
    }
    SyntheticSignal parameters = DeterministicAudioParameters.parseSyntheticSignal(node);
    AudioFormatDescriptor format =
        new AudioFormatDescriptor(
            parameters.sampleRateHz(), parameters.channels(), SOURCE_SAMPLE_SIZE_BITS);
    SineGenerator generator =
        new SineGenerator(
            format, parameters.frequencyHz(), parameters.amplitude(), parameters.phaseRadians());
    float[][] samples = new float[parameters.channels()][parameters.frameCount()];
    int offset = 0;
    while (offset < parameters.frameCount()) {
      requireNotCancelled(control);
      int frames = Math.min(CHUNK_FRAMES, parameters.frameCount() - offset);
      AudioBlock chunk = generator.nextBlock(frames);
      copyInto(chunk, samples, offset);
      offset += frames;
    }
    requireNotCancelled(control);
    return AudioBlock.wrap(format, samples, 0L, 0L);
  }

  private static void copyInto(AudioBlock source, float[][] target, int offset) {
    for (int channel = 0; channel < source.channels(); channel++) {
      System.arraycopy(source.channelView(channel), 0, target[channel], offset, source.frames());
    }
  }

  private static void requireNotCancelled(Control control) {
    if (control.cancellationRequested()) {
      throw new DeterministicAudioCancellationException();
    }
  }
}
