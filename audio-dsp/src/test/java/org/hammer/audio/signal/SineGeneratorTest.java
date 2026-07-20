package org.hammer.audio.signal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.junit.jupiter.api.Test;

class SineGeneratorTest {

  @Test
  void honorsInitialPhaseAndRestoresItOnReset() {
    AudioFormatDescriptor format = new AudioFormatDescriptor(8_000.0f, 1, 32);
    SineGenerator generator = new SineGenerator(format, 2_000.0d, 0.5f, Math.PI / 2.0d);

    AudioBlock first = generator.nextBlock(4);
    generator.nextBlock(3);
    generator.reset();
    AudioBlock afterReset = generator.nextBlock(4);

    float[] expected = {0.5f, 0.0f, -0.5f, 0.0f};
    assertArrayEquals(expected, first.channelView(0), 1.0e-6f);
    assertArrayEquals(expected, afterReset.channelView(0), 1.0e-6f);
    assertEquals(Math.PI / 2.0d, generator.initialPhaseRadians(), 0.0d);
  }

  @Test
  void rejectsNonFiniteFrequencyAmplitudeAndPhase() {
    AudioFormatDescriptor format = new AudioFormatDescriptor(48_000.0f, 1, 32);

    assertThrows(
        IllegalArgumentException.class,
        () -> new SineGenerator(format, Double.NaN, 0.5f, 0.0d));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SineGenerator(format, 1_000.0d, Float.NaN, 0.0d));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SineGenerator(format, 1_000.0d, 0.5f, Double.POSITIVE_INFINITY));
  }
}
