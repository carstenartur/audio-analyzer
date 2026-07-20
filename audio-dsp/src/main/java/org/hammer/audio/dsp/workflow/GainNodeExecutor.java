package org.hammer.audio.dsp.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.dsp.GainProcessor;
import org.hammer.audio.dsp.workflow.DeterministicAudioParameters.Gain;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.catalog.ExperimentNodeProtocol;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Control;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Violation;

/** Applies the shared gain processor to an immutable audio value in bounded chunks. */
final class GainNodeExecutor implements DeterministicAudioNodeExecutor {

  private static final int CHUNK_FRAMES = 4_096;

  @Override
  public String nodeType() {
    return ExperimentNodeProtocol.TYPE_GAIN;
  }

  @Override
  public List<Violation> validate(Node node, List<Edge> incomingEdges) {
    Objects.requireNonNull(node, "node");
    Objects.requireNonNull(incomingEdges, "incomingEdges");
    List<Violation> violations = new ArrayList<>();
    if (incomingEdges.size() != 1) {
      violations.add(
          new Violation(
              DeterministicAudioDiagnostics.INVALID_TOPOLOGY,
              "Gain nodes require exactly one incoming audio edge, found "
                  + incomingEdges.size(),
              node.id()));
    }
    try {
      DeterministicAudioParameters.parseGain(node);
    } catch (IllegalArgumentException exception) {
      violations.add(
          new Violation(
              DeterministicAudioDiagnostics.INVALID_PARAMETER,
              exception.getMessage(),
              node.id()));
    }
    return List.copyOf(violations);
  }

  @Override
  public AudioBlock execute(Node node, AudioBlock input, Control control) {
    Objects.requireNonNull(node, "node");
    Objects.requireNonNull(control, "control");
    if (input == null) {
      throw new IllegalArgumentException("Gain nodes require one computed audio input");
    }
    Gain parameters = DeterministicAudioParameters.parseGain(node);
    GainProcessor processor = new GainProcessor(parameters.factor());
    float[][] output = new float[input.channels()][input.frames()];
    int offset = 0;
    while (offset < input.frames()) {
      requireNotCancelled(control);
      int frames = Math.min(CHUNK_FRAMES, input.frames() - offset);
      AudioBlock chunk = inputChunk(input, offset, frames);
      AudioBlock processed = processor.process(chunk);
      copyInto(processed, output, offset);
      offset += frames;
    }
    requireNotCancelled(control);
    return AudioBlock.wrap(input.format(), output, input.frameIndex(), 0L);
  }

  private static AudioBlock inputChunk(AudioBlock input, int offset, int frames) {
    float[][] samples = new float[input.channels()][frames];
    for (int channel = 0; channel < input.channels(); channel++) {
      System.arraycopy(input.channelView(channel), offset, samples[channel], 0, frames);
    }
    return AudioBlock.wrap(input.format(), samples, input.frameIndex() + offset, 0L);
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
