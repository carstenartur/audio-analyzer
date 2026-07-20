package org.hammer.audio.signal;

import java.util.Objects;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;

/**
 * Deterministic mono sine-wave generator.
 *
 * <p>Produces {@code amplitude * sin(2π * frequency * t + initialPhase)} samples, sampled at {@code
 * format.sampleRate()}. Phase is tracked across calls in double precision to avoid drift over long
 * runs.
 *
 * <p>Although mono internally, the generator can be configured with a multi-channel format; in that
 * case the same signal is broadcast on every channel.
 *
 * @author refactoring
 */
public final class SineGenerator implements SignalGenerator {

  private static final double TWO_PI = 2.0d * Math.PI;

  private final AudioFormatDescriptor format;
  private final double frequencyHz;
  private final float amplitude;
  private final double phaseStep;
  private final double initialPhase;
  private double phase;
  private long frameIndex;

  /**
   * Create a new sine generator with zero initial phase.
   *
   * @param format output format descriptor
   * @param frequencyHz oscillator frequency in Hz; must be finite and {@code > 0}
   * @param amplitude peak amplitude in normalized units
   */
  public SineGenerator(AudioFormatDescriptor format, double frequencyHz, float amplitude) {
    this(format, frequencyHz, amplitude, 0.0d);
  }

  /**
   * Create a new sine generator with an explicit initial phase.
   *
   * @param format output format descriptor
   * @param frequencyHz oscillator frequency in Hz; must be finite and {@code > 0}
   * @param amplitude peak amplitude in normalized units
   * @param initialPhaseRadians finite initial phase in radians
   */
  public SineGenerator(
      AudioFormatDescriptor format,
      double frequencyHz,
      float amplitude,
      double initialPhaseRadians) {
    this.format = Objects.requireNonNull(format, "format");
    if (!Double.isFinite(frequencyHz) || !(frequencyHz > 0.0d)) {
      throw new IllegalArgumentException("frequencyHz must be finite and > 0, was " + frequencyHz);
    }
    if (!Float.isFinite(amplitude)) {
      throw new IllegalArgumentException("amplitude must be finite, was " + amplitude);
    }
    if (!Double.isFinite(initialPhaseRadians)) {
      throw new IllegalArgumentException(
          "initialPhaseRadians must be finite, was " + initialPhaseRadians);
    }
    this.frequencyHz = frequencyHz;
    this.amplitude = amplitude;
    this.phaseStep = TWO_PI * frequencyHz / format.sampleRate();
    this.initialPhase = normalizePhase(initialPhaseRadians);
    this.phase = initialPhase;
    this.frameIndex = 0L;
  }

  @Override
  public AudioFormatDescriptor format() {
    return format;
  }

  @Override
  public AudioBlock nextBlock(int frames) {
    if (frames < 1) {
      throw new IllegalArgumentException("frames must be >= 1");
    }
    int channels = format.channels();
    float[][] samples = new float[channels][frames];
    double currentPhase = phase;
    for (int frame = 0; frame < frames; frame++) {
      float value = (float) (Math.sin(currentPhase) * amplitude);
      for (int channel = 0; channel < channels; channel++) {
        samples[channel][frame] = value;
      }
      currentPhase += phaseStep;
    }
    long index = frameIndex;
    phase = normalizePhase(currentPhase);
    frameIndex += frames;
    return AudioBlock.wrap(format, samples, index, System.nanoTime());
  }

  @Override
  public void reset() {
    phase = initialPhase;
    frameIndex = 0L;
  }

  /** Returns the oscillator frequency in hertz. */
  public double frequencyHz() {
    return frequencyHz;
  }

  /** Returns the peak amplitude. */
  public float amplitude() {
    return amplitude;
  }

  /** Returns the normalized initial phase in radians. */
  public double initialPhaseRadians() {
    return initialPhase;
  }

  private static double normalizePhase(double value) {
    double normalized = value % TWO_PI;
    return normalized < 0.0d ? normalized + TWO_PI : normalized;
  }
}
