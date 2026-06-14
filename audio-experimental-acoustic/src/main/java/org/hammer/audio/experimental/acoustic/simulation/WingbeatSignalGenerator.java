package org.hammer.audio.experimental.acoustic.simulation;

import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.hammer.audio.signal.SignalGenerator;

/**
 * Deterministic synthetic wingbeat signal generator.
 *
 * <p>Produces a mono (or broadcast-stereo) audio stream whose tonal structure is controlled by a
 * {@link WingbeatSignalParameters} instance and whose noise and jitter sequences are seeded by a
 * caller-supplied {@code randomSeed}. Two instances constructed with the same parameters and seed
 * always produce bit-identical output.
 *
 * <h2>Signal model</h2>
 *
 * <p>At sample index {@code n} (time {@code t = n / sampleRate}):
 *
 * <pre>
 *   f(t)   = f₀  +  driftHzPerSecond × t  +  jitterHz × noise_j(n)
 *   φ      ← φ + 2π × f(t) / sampleRate          (phase accumulator)
 *   am(t)  = 1  +  modulationDepth × sin(2π × modulationHz × t)
 *   signal = am(t) × Σ_{k=0}^{harmonicCount-1} A_k × sin((k+1) × φ)
 *            +  noiseAmplitude × noise_w(n)
 * </pre>
 *
 * <p>where {@code noise_j} and {@code noise_w} are deterministic hash sequences derived from the
 * sample index and {@code randomSeed}. The phase accumulator is wrapped modulo {@code 2π} each
 * sample to preserve floating-point precision over long runs.
 *
 * <p>The generated signal does not model real mosquito aerodynamics. It is intended solely as a
 * controlled test fixture for algorithm development and CI verification.
 *
 * @see WingbeatSignalParameters
 */
public final class WingbeatSignalGenerator implements SignalGenerator {

  private static final double TWO_PI = 2.0 * Math.PI;

  private final AudioFormatDescriptor format;
  private final WingbeatSignalParameters params;
  private final long randomSeed;

  private double fundamentalPhase;
  private long frameIndex;

  /**
   * Create a generator.
   *
   * @param format output format; must not be {@code null}
   * @param params signal-model parameters; must not be {@code null}
   * @param randomSeed deterministic seed for jitter and noise sequences; the same seed always
   *     produces the same output for a given {@code params} and {@code format}
   */
  public WingbeatSignalGenerator(
      AudioFormatDescriptor format, WingbeatSignalParameters params, long randomSeed) {
    if (format == null) {
      throw new IllegalArgumentException("format must not be null");
    }
    if (params == null) {
      throw new IllegalArgumentException("params must not be null");
    }
    this.format = format;
    this.params = params;
    this.randomSeed = randomSeed;
    this.fundamentalPhase = 0.0;
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
    double sampleRate = format.sampleRate();
    float[][] samples = new float[channels][frames];

    for (int i = 0; i < frames; i++) {
      long absoluteFrame = frameIndex + i;
      double t = absoluteFrame / sampleRate;

      // Instantaneous frequency with drift and jitter.
      double instFreq = params.fundamentalFrequencyHz() + params.driftHzPerSecond() * t;
      if (params.jitterHz() > 0.0) {
        instFreq += params.jitterHz() * hashNoise(absoluteFrame, randomSeed);
      }
      instFreq = Math.max(1.0, instFreq);

      // Advance the fundamental phase accumulator and keep it in [0, 2π).
      fundamentalPhase += TWO_PI * instFreq / sampleRate;
      if (fundamentalPhase >= TWO_PI) {
        fundamentalPhase -= TWO_PI;
      }

      // Amplitude modulation envelope.
      double am = 1.0;
      if (params.modulationHz() > 0.0 && params.modulationDepth() > 0.0) {
        am = 1.0 + params.modulationDepth() * Math.sin(TWO_PI * params.modulationHz() * t);
      }

      // Sum harmonics.
      double signal = 0.0;
      int harmonicCount = params.harmonicCount();
      for (int k = 0; k < harmonicCount; k++) {
        signal += harmonicAmplitude(k) * Math.sin((k + 1) * fundamentalPhase);
      }
      signal *= am;

      // Additive noise (use a different hash lane from jitter).
      if (params.noiseAmplitude() > 0.0) {
        signal += params.noiseAmplitude() * hashNoise(absoluteFrame ^ 0xA5A5A5A5L, randomSeed);
      }

      float sample = (float) Math.max(-1.0, Math.min(1.0, signal));
      for (int c = 0; c < channels; c++) {
        samples[c][i] = sample;
      }
    }

    AudioBlock block = AudioBlock.wrap(format, samples, frameIndex, System.nanoTime());
    frameIndex += frames;
    return block;
  }

  @Override
  public void reset() {
    fundamentalPhase = 0.0;
    frameIndex = 0L;
  }

  /**
   * Return the {@link WingbeatSignalParameters} used by this generator.
   *
   * @return signal-model parameters
   */
  public WingbeatSignalParameters params() {
    return params;
  }

  private double harmonicAmplitude(int harmonicIndex) {
    var amplitudes = params.harmonicAmplitudes();
    if (amplitudes == null || harmonicIndex >= amplitudes.size()) {
      return 1.0;
    }
    return amplitudes.get(harmonicIndex);
  }

  /**
   * Deterministic pseudo-random value in {@code [-1, 1]} derived from a frame index and a seed.
   *
   * <p>Uses a single LCG mix (same scheme as {@code DemoPresetGenerator}) so that every sample maps
   * to a stable, reproducible value with no shared state between lanes.
   */
  private static double hashNoise(long frame, long seed) {
    long v = frame ^ (seed * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L);
    v *= 6_364_136_223_846_793_005L;
    v ^= v >>> 33;
    return ((v & 0xffffL) / 32767.5) - 1.0;
  }
}
