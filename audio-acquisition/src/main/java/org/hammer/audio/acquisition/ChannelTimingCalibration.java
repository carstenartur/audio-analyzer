package org.hammer.audio.acquisition;

/**
 * Affine timing and level calibration for one microphone channel.
 *
 * @param channel zero-based channel index
 * @param referenceFrame frame at which {@code offsetSamples} was measured
 * @param offsetSamples observed channel delay relative to the profile reference channel
 * @param driftPpm relative sample-rate drift in parts per million
 * @param residualRmsSamples RMS calibration-fit residual in samples
 * @param jitterRmsSamples RMS short-term timing jitter in samples
 * @param gainLinear multiplicative level correction
 * @param invertedPolarity whether samples must be polarity-inverted
 */
public record ChannelTimingCalibration(
    int channel,
    long referenceFrame,
    double offsetSamples,
    double driftPpm,
    double residualRmsSamples,
    double jitterRmsSamples,
    double gainLinear,
    boolean invertedPolarity) {

  /** Creates one validated channel calibration. */
  public ChannelTimingCalibration {
    if (channel < 0) {
      throw new IllegalArgumentException("channel must be >= 0");
    }
    if (referenceFrame < 0) {
      throw new IllegalArgumentException("referenceFrame must be >= 0");
    }
    requireFinite(offsetSamples, "offsetSamples");
    requireFinite(driftPpm, "driftPpm");
    requireNonNegativeFinite(residualRmsSamples, "residualRmsSamples");
    requireNonNegativeFinite(jitterRmsSamples, "jitterRmsSamples");
    if (!(gainLinear > 0.0) || !Double.isFinite(gainLinear)) {
      throw new IllegalArgumentException("gainLinear must be finite and > 0");
    }
  }

  /** Returns the predicted channel delay at an absolute nominal frame index. */
  public double offsetAtFrame(long frameIndex) {
    if (frameIndex < 0) {
      throw new IllegalArgumentException("frameIndex must be >= 0");
    }
    return offsetSamples + (frameIndex - referenceFrame) * driftPpm * 1.0e-6;
  }

  /** Conservative one-sigma timing uncertainty in samples. */
  public double timingUncertaintySamples() {
    return Math.hypot(residualRmsSamples, jitterRmsSamples);
  }

  private static void requireFinite(double value, String name) {
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException(name + " must be finite");
    }
  }

  private static void requireNonNegativeFinite(double value, String name) {
    if (value < 0.0 || !Double.isFinite(value)) {
      throw new IllegalArgumentException(name + " must be finite and >= 0");
    }
  }
}
