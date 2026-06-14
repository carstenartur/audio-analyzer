package org.hammer.audio.experimental.acoustic.simulation;

import java.util.Collections;
import java.util.List;
import org.hammer.audio.experimental.acoustic.scenario.AcousticGroundTruth;

/**
 * Immutable signal-model parameters for a synthetic wingbeat-like source.
 *
 * <p>The model is a sum of sinusoidal partials (fundamental + harmonics) with optional amplitude
 * modulation, linear frequency drift, per-sample frequency jitter and additive white noise:
 *
 * <pre>
 *   f(t)   = fundamentalFrequencyHz + driftHzPerSecond * t + jitterHz * noise(t)
 *   am(t)  = 1 + modulationDepth * sin(2π * modulationHz * t)         (if modulationHz &gt; 0)
 *   signal = am(t) * Σ_{k=0}^{harmonicCount-1} A_k * sin((k+1) * φ(t))
 *            + noiseAmplitude * white_noise(t)
 * </pre>
 *
 * <p>where {@code A_k} is {@link #harmonicAmplitudes()}{@code .get(k)} when that list is provided,
 * or {@code 1.0} otherwise, and {@code φ(t)} is the phase accumulated by integrating {@code f(t)}.
 *
 * <p>This model does not reproduce the aerodynamics of a real wing. Its purpose is to produce
 * controlled, parameterised test signals with known ground truth so that feature-extraction and
 * classification algorithms can be verified before real labelled recordings are available.
 *
 * @param fundamentalFrequencyHz emitted fundamental frequency in Hz; must be finite and positive
 * @param harmonicCount number of partials (1 = fundamental only); must be &gt;= 1
 * @param harmonicAmplitudes per-partial amplitude coefficients for indices {@code 0 ..
 *     harmonicCount-1}; {@code null} means all coefficients equal {@code 1.0}; when non-null its
 *     size must equal {@link #harmonicCount()}. TODO: Replace this flat list with a HarmonicProfile
 *     abstraction once synthetic fixtures need harmonic phases or rolloff metadata.
 * @param modulationHz amplitude-modulation frequency in Hz; {@code 0} disables AM
 * @param modulationDepth AM depth in {@code [0, 1]}; {@code 0} disables AM
 * @param driftHzPerSecond linear frequency drift per second; {@code 0} for no drift
 * @param jitterHz maximum signed per-sample frequency deviation from the instantaneous fundamental,
 *     so the peak-to-peak jitter span is {@code 2 * jitterHz}; {@code 0} for no jitter
 * @param noiseAmplitude additive white-noise amplitude in {@code [0, 1]}; {@code 0} for no noise
 */
public record WingbeatSignalParameters(
    double fundamentalFrequencyHz,
    int harmonicCount,
    List<Double> harmonicAmplitudes,
    double modulationHz,
    double modulationDepth,
    double driftHzPerSecond,
    double jitterHz,
    double noiseAmplitude) {

  public WingbeatSignalParameters {
    if (!Double.isFinite(fundamentalFrequencyHz) || fundamentalFrequencyHz <= 0.0) {
      throw new IllegalArgumentException("fundamentalFrequencyHz must be finite and > 0");
    }
    if (harmonicCount < 1) {
      throw new IllegalArgumentException("harmonicCount must be >= 1");
    }
    if (harmonicAmplitudes != null) {
      if (harmonicAmplitudes.size() != harmonicCount) {
        throw new IllegalArgumentException(
            "harmonicAmplitudes.size() must equal harmonicCount when provided");
      }
      for (int i = 0; i < harmonicAmplitudes.size(); i++) {
        Double amplitude = harmonicAmplitudes.get(i);
        if (amplitude == null) {
          throw new IllegalArgumentException(
              "harmonicAmplitudes[%d] must not be null".formatted(i));
        }
        if (Double.isNaN(amplitude)) {
          throw new IllegalArgumentException("harmonicAmplitudes[%d] must not be NaN".formatted(i));
        }
        if (Double.isInfinite(amplitude)) {
          throw new IllegalArgumentException("harmonicAmplitudes[%d] must be finite".formatted(i));
        }
      }

      harmonicAmplitudes = List.copyOf(harmonicAmplitudes);
    }
    if (!Double.isFinite(modulationHz) || modulationHz < 0.0) {
      throw new IllegalArgumentException("modulationHz must be finite and >= 0");
    }
    if (!Double.isFinite(modulationDepth) || modulationDepth < 0.0 || modulationDepth > 1.0) {
      throw new IllegalArgumentException("modulationDepth must be in [0, 1]");
    }
    if (!Double.isFinite(driftHzPerSecond)) {
      throw new IllegalArgumentException("driftHzPerSecond must be finite");
    }
    if (!Double.isFinite(jitterHz) || jitterHz < 0.0) {
      throw new IllegalArgumentException("jitterHz must be finite and >= 0");
    }
    if (!Double.isFinite(noiseAmplitude) || noiseAmplitude < 0.0 || noiseAmplitude > 1.0) {
      throw new IllegalArgumentException("noiseAmplitude must be in [0, 1]");
    }
  }

  @Override
  public List<Double> harmonicAmplitudes() {
    return harmonicAmplitudes == null ? null : List.copyOf(harmonicAmplitudes);
  }

  /**
   * Return the fully resolved harmonic amplitude profile used by the generator and ground truth.
   */
  public List<Double> resolvedHarmonicAmplitudes() {
    if (harmonicAmplitudes == null) {
      return Collections.nCopies(harmonicCount, 1.0);
    }
    return harmonicAmplitudes();
  }

  /**
   * Create a minimal single-tone parameter set (fundamental only, no modulation, no noise).
   *
   * @param fundamentalFrequencyHz fundamental frequency in Hz; must be finite and positive
   */
  public static WingbeatSignalParameters of(double fundamentalFrequencyHz) {
    return new WingbeatSignalParameters(fundamentalFrequencyHz, 1, null, 0.0, 0.0, 0.0, 0.0, 0.0);
  }

  /**
   * Create a parameter set that approximates a mosquito-like wingbeat signal with four harmonics, a
   * small frequency drift, light jitter and a low noise floor.
   *
   * <p>The amplitudes follow a {@code 1 / (k+1)} profile (fundamental loudest). This is a rough
   * phenomenological approximation; it does not represent any validated biological model.
   *
   * @param fundamentalFrequencyHz fundamental frequency in Hz; must be finite and positive
   */
  public static WingbeatSignalParameters mosquitoLike(double fundamentalFrequencyHz) {
    return new WingbeatSignalParameters(
        fundamentalFrequencyHz, 4, List.of(1.0, 0.5, 0.25, 0.125), 0.0, 0.0, 0.5, 1.0, 0.02);
  }

  /**
   * Convert these parameters to an {@link AcousticGroundTruth} record suitable for benchmark
   * comparison.
   *
   * <p>The mapping is:
   *
   * <ul>
   *   <li>{@link #fundamentalFrequencyHz()} → {@link AcousticGroundTruth#fundamentalFrequencyHz()}
   *   <li>{@link #harmonicCount()} → {@link AcousticGroundTruth#harmonicCount()}
   *   <li>{@link #resolvedHarmonicAmplitudes()} → {@link AcousticGroundTruth#harmonics()}
   *   <li>{@link #modulationHz()} → {@link AcousticGroundTruth#modulationFrequencyHz()}
   *   <li>{@link #modulationDepth()} → {@link AcousticGroundTruth#modulationDepth()}
   *   <li>{@link #jitterHz()} → {@link AcousticGroundTruth#jitter()} as a maximum signed deviation
   *       in Hz
   *   <li>{@link #driftHzPerSecond()} → {@link AcousticGroundTruth#drift()}
   *   <li>{@link #noiseAmplitude()} → {@link AcousticGroundTruth#noiseAmplitude()}
   * </ul>
   */
  public AcousticGroundTruth toGroundTruth() {
    return new AcousticGroundTruth(
        fundamentalFrequencyHz,
        harmonicCount,
        resolvedHarmonicAmplitudes(),
        modulationHz,
        modulationDepth,
        jitterHz,
        driftHzPerSecond,
        noiseAmplitude,
        null);
  }
}
