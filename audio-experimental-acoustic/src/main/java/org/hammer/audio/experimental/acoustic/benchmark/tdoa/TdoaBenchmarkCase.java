package org.hammer.audio.experimental.acoustic.benchmark.tdoa;

import java.util.Objects;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.core.AudioBlock;

/**
 * One deterministic known-delay input shared by every registered TDOA estimator.
 *
 * @param caseId stable benchmark-case identity
 * @param block immutable multichannel audio block
 * @param array microphone geometry matching the block
 * @param firstChannel first estimator channel
 * @param secondChannel second estimator channel
 * @param expectedDelaySamples known second-minus-first delay in samples
 */
public record TdoaBenchmarkCase(
    String caseId,
    AudioBlock block,
    MicrophoneArray array,
    int firstChannel,
    int secondChannel,
    double expectedDelaySamples) {

  // Validate one benchmark case.
  public TdoaBenchmarkCase {
    if (caseId == null || caseId.isBlank()) {
      throw new IllegalArgumentException("caseId must not be blank");
    }
    Objects.requireNonNull(block, "block");
    Objects.requireNonNull(array, "array");
    if (block.channels() != array.channels()) {
      throw new IllegalArgumentException("block channel count must match microphone array");
    }
    if (firstChannel < 0
        || secondChannel < 0
        || firstChannel >= array.channels()
        || secondChannel >= array.channels()
        || firstChannel == secondChannel) {
      throw new IllegalArgumentException("benchmark channels must be distinct valid array channels");
    }
    if (!Double.isFinite(expectedDelaySamples)) {
      throw new IllegalArgumentException("expectedDelaySamples must be finite");
    }
  }
}
