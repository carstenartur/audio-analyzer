package org.hammer.audio.dsp;

import java.util.Objects;
import org.hammer.audio.core.AudioBlock;

/**
 * Applies a finite non-negative linear gain and saturates samples to the normalized audio range.
 */
public final class GainProcessor implements DSPProcessor {

  /** Conservative upper bound that prevents accidental extreme amplification. */
  public static final float MAX_GAIN = 16.0f;

  private final float gain;

  /**
   * Creates a processor with one stable linear multiplier.
   *
   * @param gain finite multiplier in {@code [0, 16]}
   */
  public GainProcessor(float gain) {
    if (!Float.isFinite(gain) || gain < 0.0f || gain > MAX_GAIN) {
      throw new IllegalArgumentException("gain must be finite and in [0, 16], was " + gain);
    }
    this.gain = gain;
  }

  /** Returns the configured linear multiplier. */
  public float gain() {
    return gain;
  }

  @Override
  public AudioBlock process(AudioBlock input) {
    Objects.requireNonNull(input, "input");
    float[][] output = new float[input.channels()][input.frames()];
    for (int channel = 0; channel < input.channels(); channel++) {
      float[] source = input.channelView(channel);
      float[] target = output[channel];
      for (int frame = 0; frame < input.frames(); frame++) {
        target[frame] = clamp(source[frame] * gain);
      }
    }
    return AudioBlock.wrap(input.format(), output, input.frameIndex(), input.timestampNanos());
  }

  private static float clamp(float sample) {
    return Math.max(-1.0f, Math.min(1.0f, sample));
  }
}
