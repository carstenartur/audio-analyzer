package org.hammer.audio.experimental.acoustic.calibration;

import java.util.Objects;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;

/** Deterministic single-pulse audio fixtures for calibration tests and offline experiments. */
public final class SyntheticCalibrationFixture {

  private SyntheticCalibrationFixture() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Generates one common pulse observed with an explicit non-negative delay per channel.
   *
   * @param format multichannel audio format
   * @param frameIndex absolute nominal start frame
   * @param timestampNanos nominal block timestamp
   * @param frames block length
   * @param pulseFrame undelayed pulse frame inside the block
   * @param channelDelaysSamples per-channel delay from the common pulse
   * @return immutable deterministic calibration block
   */
  public static AudioBlock pulseBlock(
      AudioFormatDescriptor format,
      long frameIndex,
      long timestampNanos,
      int frames,
      int pulseFrame,
      int[] channelDelaysSamples) {
    Objects.requireNonNull(format, "format");
    Objects.requireNonNull(channelDelaysSamples, "channelDelaysSamples");
    if (frames < 1) {
      throw new IllegalArgumentException("frames must be >= 1");
    }
    if (pulseFrame < 0 || pulseFrame >= frames) {
      throw new IllegalArgumentException("pulseFrame must be inside the block");
    }
    if (channelDelaysSamples.length != format.channels()) {
      throw new IllegalArgumentException("channel delays must match the format channel count");
    }
    float[][] samples = new float[format.channels()][frames];
    for (int channel = 0; channel < channelDelaysSamples.length; channel++) {
      int delay = channelDelaysSamples[channel];
      int observedFrame = pulseFrame + delay;
      if (delay < 0 || observedFrame >= frames) {
        throw new IllegalArgumentException("channel delay places the pulse outside the block");
      }
      samples[channel][observedFrame] = 1.0f;
    }
    return AudioBlock.wrap(format, samples, frameIndex, timestampNanos);
  }
}
