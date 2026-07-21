package org.hammer.audio.experimental.acoustic.calibration;

import java.util.List;
import java.util.Objects;

/**
 * Estimated relative channel delays for one simultaneous calibration event.
 *
 * @param frameIndex absolute nominal frame of the observation block
 * @param referenceChannel zero-delay reference channel
 * @param offsetsSamples observed per-channel delay relative to the reference channel
 * @param confidences normalized per-channel correlation confidence
 */
public record CalibrationEventObservation(
    long frameIndex,
    int referenceChannel,
    List<Double> offsetsSamples,
    List<Double> confidences) {

  /** Creates a complete immutable event observation. */
  public CalibrationEventObservation {
    if (frameIndex < 0) {
      throw new IllegalArgumentException("frameIndex must be >= 0");
    }
    offsetsSamples = List.copyOf(Objects.requireNonNull(offsetsSamples, "offsetsSamples"));
    confidences = List.copyOf(Objects.requireNonNull(confidences, "confidences"));
    if (offsetsSamples.isEmpty() || offsetsSamples.size() != confidences.size()) {
      throw new IllegalArgumentException("offset and confidence lists must be non-empty and equal");
    }
    if (referenceChannel < 0 || referenceChannel >= offsetsSamples.size()) {
      throw new IllegalArgumentException("referenceChannel must exist in observations");
    }
    for (int channel = 0; channel < offsetsSamples.size(); channel++) {
      double offset = offsetsSamples.get(channel);
      double confidence = confidences.get(channel);
      if (!Double.isFinite(offset)) {
        throw new IllegalArgumentException("offsets must be finite");
      }
      if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
        throw new IllegalArgumentException("confidences must be finite and in [0,1]");
      }
    }
    if (Math.abs(offsetsSamples.get(referenceChannel)) > 1.0e-9) {
      throw new IllegalArgumentException("reference channel offset must be zero");
    }
  }

  /** Number of observed channels. */
  public int channels() {
    return offsetsSamples.size();
  }
}
