package org.hammer.audio.dsp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.junit.jupiter.api.Test;

class GainProcessorTest {

  private static final AudioFormatDescriptor FORMAT = new AudioFormatDescriptor(48_000.0f, 1, 32);

  @Test
  void appliesLinearGainWithoutMutatingInput() {
    AudioBlock input = new AudioBlock(FORMAT, new float[][] {{-0.5f, 0.0f, 0.5f}}, 7L, 11L);

    AudioBlock output = new GainProcessor(0.5f).process(input);

    assertArrayEquals(new float[] {-0.25f, 0.0f, 0.25f}, output.channelView(0), 0.0f);
    assertArrayEquals(new float[] {-0.5f, 0.0f, 0.5f}, input.channelView(0), 0.0f);
    assertEquals(7L, output.frameIndex());
    assertEquals(11L, output.timestampNanos());
  }

  @Test
  void saturatesToNormalizedAudioRange() {
    AudioBlock input = new AudioBlock(FORMAT, new float[][] {{-0.75f, 0.75f}}, 0L, 0L);

    AudioBlock output = new GainProcessor(2.0f).process(input);

    assertArrayEquals(new float[] {-1.0f, 1.0f}, output.channelView(0), 0.0f);
  }

  @Test
  void rejectsNonFiniteNegativeAndExcessiveGain() {
    assertThrows(IllegalArgumentException.class, () -> new GainProcessor(Float.NaN));
    assertThrows(IllegalArgumentException.class, () -> new GainProcessor(-0.1f));
    assertThrows(
        IllegalArgumentException.class, () -> new GainProcessor(GainProcessor.MAX_GAIN + 0.1f));
  }
}
