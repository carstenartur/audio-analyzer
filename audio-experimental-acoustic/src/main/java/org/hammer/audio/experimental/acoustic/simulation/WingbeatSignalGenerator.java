package org.hammer.audio.experimental.acoustic.simulation;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * sample, even for arbitrarily large phase increments, to preserve floating-point precision over
 * long runs.
 *
 * <p>The generated signal does not model real mosquito aerodynamics. It is intended solely as a
 * controlled test fixture for algorithm development and CI verification.
 *
 * @see WingbeatSignalParameters
 */
public final class WingbeatSignalGenerator implements SignalGenerator {

  private static final double TWO_PI = 2.0 * Math.PI;
  private static final Map<PhaseCacheKey, PhaseAccumulatorCache> PHASE_CACHES =
      new ConcurrentHashMap<>();

  private final AudioFormatDescriptor format;
  private final WingbeatSignalParameters params;
  private final List<Double> harmonicAmplitudes;
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
    this.harmonicAmplitudes = params.resolvedHarmonicAmplitudes();
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
      double instFreq = instantaneousFrequency(params, absoluteFrame, sampleRate, randomSeed);

      // Advance the fundamental phase accumulator and keep it in [0, 2π).
      fundamentalPhase = wrapPhase(fundamentalPhase + phaseIncrement(instFreq, sampleRate));

      // Amplitude modulation envelope.
      double am = modulationEnvelope(params, t);

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

  double currentFundamentalPhase() {
    return fundamentalPhase;
  }

  private double harmonicAmplitude(int harmonicIndex) {
    return harmonicAmplitudes.get(harmonicIndex);
  }

  static double sampleAtTime(
      WingbeatSignalParameters params, long randomSeed, double sampleRate, double seconds) {
    return sampleAtTime(params, randomSeed, sampleRate, seconds, 1.0);
  }

  static double sampleAtTime(
      WingbeatSignalParameters params,
      long randomSeed,
      double sampleRate,
      double seconds,
      double frequencyScale) {
    if (params == null) {
      throw new IllegalArgumentException("params must not be null");
    }
    if (!(sampleRate > 0.0) || !Double.isFinite(sampleRate)) {
      throw new IllegalArgumentException("sampleRate must be finite and > 0");
    }
    if (!Double.isFinite(seconds)) {
      throw new IllegalArgumentException("seconds must be finite");
    }
    if (!(frequencyScale > 0.0) || !Double.isFinite(frequencyScale)) {
      throw new IllegalArgumentException("frequencyScale must be finite and > 0");
    }

    double samplePosition = seconds * sampleRate;
    long wholeFrames = (long) Math.floor(samplePosition);
    double fractionalFrame = samplePosition - wholeFrames;
    double phase = phaseAtFrame(params, randomSeed, sampleRate, wholeFrames, frequencyScale);
    if (fractionalFrame > 0.0) {
      double instFreqAtTime =
          instantaneousFrequencyAtTime(params, seconds, wholeFrames, randomSeed) * frequencyScale;
      phase = wrapPhase(phase + fractionalFrame * phaseIncrement(instFreqAtTime, sampleRate));
    }

    double signal = 0.0;
    List<Double> harmonicAmplitudes = params.resolvedHarmonicAmplitudes();
    for (int k = 0; k < params.harmonicCount(); k++) {
      signal += harmonicAmplitudes.get(k) * Math.sin((k + 1) * phase);
    }
    signal *= modulationEnvelope(params, seconds);
    if (params.noiseAmplitude() > 0.0) {
      signal += params.noiseAmplitude() * hashNoise(wholeFrames ^ 0xA5A5A5A5L, randomSeed);
    }
    return Math.max(-1.0, Math.min(1.0, signal));
  }

  private static double phaseAtFrame(
      WingbeatSignalParameters params,
      long randomSeed,
      double sampleRate,
      long frameIndex,
      double frequencyScale) {
    PhaseCacheKey key = new PhaseCacheKey(params, randomSeed, sampleRate, frequencyScale);
    return PHASE_CACHES
        .computeIfAbsent(key, unused -> new PhaseAccumulatorCache(params, randomSeed, sampleRate))
        .phaseAt(frameIndex, frequencyScale);
  }

  private static double instantaneousFrequency(
      WingbeatSignalParameters params, long frameIndex, double sampleRate, long randomSeed) {
    return instantaneousFrequencyAtTime(params, frameIndex / sampleRate, frameIndex, randomSeed);
  }

  private static double instantaneousFrequencyAtTime(
      WingbeatSignalParameters params, double seconds, long noiseFrameIndex, long randomSeed) {
    double instFreq = params.fundamentalFrequencyHz() + params.driftHzPerSecond() * seconds;
    if (params.jitterHz() > 0.0) {
      instFreq += params.jitterHz() * hashNoise(noiseFrameIndex, randomSeed);
    }
    return Math.max(1.0, instFreq);
  }

  private static double phaseIncrement(double instantaneousFrequencyHz, double sampleRate) {
    return TWO_PI * instantaneousFrequencyHz / sampleRate;
  }

  private static double modulationEnvelope(WingbeatSignalParameters params, double seconds) {
    if (params.modulationHz() <= 0.0 || params.modulationDepth() <= 0.0) {
      return 1.0;
    }
    return 1.0 + params.modulationDepth() * Math.sin(TWO_PI * params.modulationHz() * seconds);
  }

  private static double wrapPhase(double phase) {
    double wrapped = phase % TWO_PI;
    return wrapped >= 0.0 ? wrapped : wrapped + TWO_PI;
  }

  private record PhaseCacheKey(
      WingbeatSignalParameters params, long randomSeed, double sampleRate, double frequencyScale) {}

  private static final class PhaseAccumulatorCache {

    private final WingbeatSignalParameters params;
    private final long randomSeed;
    private final double sampleRate;
    private final Map<Long, Double> phaseByFrame = new ConcurrentHashMap<>();

    private long maxComputedPositiveFrame = -1L;
    private double phaseAtMaxComputedPositiveFrame = 0.0;
    private long minComputedNegativeFrame = 0L;
    private double phaseAtMinComputedNegativeFrame = 0.0;

    private PhaseAccumulatorCache(
        WingbeatSignalParameters params, long randomSeed, double sampleRate) {
      this.params = params;
      this.randomSeed = randomSeed;
      this.sampleRate = sampleRate;
    }

    private synchronized double phaseAt(long frameIndex, double frequencyScale) {
      Double cachedPhase = phaseByFrame.get(frameIndex);
      if (cachedPhase != null) {
        return cachedPhase;
      }
      if (frameIndex >= 0L) {
        return extendPositive(frameIndex, frequencyScale);
      }
      return extendNegative(frameIndex, frequencyScale);
    }

    private double extendPositive(long targetFrame, double frequencyScale) {
      double phase = phaseAtMaxComputedPositiveFrame;
      for (long frame = maxComputedPositiveFrame + 1L; frame <= targetFrame; frame++) {
        phase =
            wrapPhase(
                phase
                    + phaseIncrement(
                        instantaneousFrequency(params, frame, sampleRate, randomSeed)
                            * frequencyScale,
                        sampleRate));
        phaseByFrame.put(frame, phase);
      }
      maxComputedPositiveFrame = targetFrame;
      phaseAtMaxComputedPositiveFrame = phase;
      return phase;
    }

    private double extendNegative(long targetFrame, double frequencyScale) {
      double phase = phaseAtMinComputedNegativeFrame;
      for (long frame = minComputedNegativeFrame - 1L; frame >= targetFrame; frame--) {
        phase =
            wrapPhase(
                phase
                    - phaseIncrement(
                        instantaneousFrequency(params, frame, sampleRate, randomSeed)
                            * frequencyScale,
                        sampleRate));
        phaseByFrame.put(frame, phase);
      }
      minComputedNegativeFrame = targetFrame;
      phaseAtMinComputedNegativeFrame = phase;
      return phase;
    }
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
