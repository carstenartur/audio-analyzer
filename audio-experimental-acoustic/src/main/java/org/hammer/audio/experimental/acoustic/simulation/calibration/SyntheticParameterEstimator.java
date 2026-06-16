package org.hammer.audio.experimental.acoustic.simulation.calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToDoubleFunction;
import org.hammer.audio.experimental.acoustic.simulation.WingbeatSignalParameters;
import org.hammer.audio.experimental.acoustic.wingbeat.WingbeatFeatureVector;

/**
 * Estimates {@link WingbeatSignalParameters} from statistical properties of real recordings.
 *
 * <p>Each generator parameter is inferred from the corresponding feature statistic:
 *
 * <ul>
 *   <li>{@code fundamentalFrequencyHz} ← mean fundamental frequency across real vectors
 *   <li>{@code jitterHz} ← mean frequency-jitter (std dev) scaled by √3 to convert to the
 *       generator's uniform max-deviation semantics; see {@link
 *       WingbeatSignalParameters#jitterHz()}
 *   <li>{@code modulationDepth} ← mean amplitude modulation, clamped to {@code [0, 1]}
 *   <li>{@code noiseAmplitude} ← approximated from mean SNR as {@code 1 / (snr + 1)}, clamped to
 *       {@code [0, 1]}; falls back to {@value #DEFAULT_NOISE_AMPLITUDE} when SNR is zero
 *   <li>{@code harmonicAmplitudes} ← per-slot mean across real vectors, normalised so the maximum
 *       element equals {@code 1.0}; defaults to a single-harmonic {@code null} profile when fewer
 *       than two harmonic amplitude values are available
 *   <li>{@code harmonicCount} ← derived from the estimated harmonic amplitude list
 *   <li>{@code driftHzPerSecond} ← mean frequency drift across real vectors
 *   <li>{@code modulationHz} ← cannot be estimated from {@link WingbeatFeatureVector}; fixed at
 *       {@code 0}
 * </ul>
 *
 * <p>This estimator is stateless and may be called concurrently.
 */
public final class SyntheticParameterEstimator {

  /** Fallback noise amplitude used when the real data reports zero SNR. */
  static final double DEFAULT_NOISE_AMPLITUDE = 0.02;

  /** Minimum fundamental frequency returned by the estimator. */
  private static final double MIN_FREQ_HZ = 1.0;

  /**
   * Estimate {@link WingbeatSignalParameters} from a non-empty list of real feature vectors.
   *
   * @param real real recording feature vectors; must not be {@code null} or empty
   * @return estimated generator parameters; never {@code null}
   */
  public WingbeatSignalParameters estimate(List<WingbeatFeatureVector> real) {
    Objects.requireNonNull(real, "real");
    if (real.isEmpty()) {
      throw new IllegalArgumentException("real corpus must not be empty");
    }

    double meanFreq = mean(real, WingbeatFeatureVector::fundamentalFrequencyHz);
    double meanJitter = mean(real, WingbeatFeatureVector::frequencyJitterHz);
    double meanModulation = mean(real, WingbeatFeatureVector::amplitudeModulation);
    double meanSnr = mean(real, WingbeatFeatureVector::signalToNoiseRatio);
    double meanDrift = mean(real, WingbeatFeatureVector::frequencyDriftHzPerSecond);

    double fundamentalFreq = Math.max(MIN_FREQ_HZ, meanFreq);
    double jitterHz = Math.max(0.0, meanJitter * Math.sqrt(3.0));
    double modulationDepth = Math.max(0.0, Math.min(1.0, meanModulation));
    double noiseAmplitude = estimateNoiseAmplitude(meanSnr);

    List<Double> harmonicAmplitudes = estimateHarmonicAmplitudes(real);
    int harmonicCount = harmonicAmplitudes.size();

    // Use null to signal uniform amplitude (all 1.0) when no harmonic data is available
    List<Double> ampParam = harmonicCount > 1 ? harmonicAmplitudes : null;

    return new WingbeatSignalParameters(
        fundamentalFreq,
        Math.max(1, harmonicCount),
        ampParam,
        0.0,
        modulationDepth,
        meanDrift,
        jitterHz,
        noiseAmplitude);
  }

  private static List<Double> estimateHarmonicAmplitudes(List<WingbeatFeatureVector> vectors) {
    int maxHarmonics = 0;
    for (WingbeatFeatureVector v : vectors) {
      maxHarmonics = Math.max(maxHarmonics, v.harmonicAmplitudes().size());
    }
    if (maxHarmonics < 2) {
      // Insufficient harmonic data — return single-element list so caller uses null profile
      return List.of(1.0);
    }
    double[] sums = new double[maxHarmonics];
    int[] counts = new int[maxHarmonics];
    for (WingbeatFeatureVector v : vectors) {
      List<Double> amps = v.harmonicAmplitudes();
      for (int i = 0; i < amps.size(); i++) {
        sums[i] += amps.get(i);
        counts[i]++;
      }
    }
    double maxAmp = 0.0;
    double[] means = new double[maxHarmonics];
    for (int i = 0; i < maxHarmonics; i++) {
      means[i] = counts[i] > 0 ? sums[i] / counts[i] : 0.0;
      if (means[i] > maxAmp) {
        maxAmp = means[i];
      }
    }
    if (maxAmp <= 0.0) {
      return List.of(1.0);
    }
    List<Double> result = new ArrayList<>(maxHarmonics);
    for (double m : means) {
      result.add(m / maxAmp);
    }
    return List.copyOf(result);
  }

  /**
   * Approximate noise amplitude from the measured SNR.
   *
   * <p>Uses {@code noiseAmplitude ≈ 1 / (snr + 1)} so that higher SNR produces lower noise. Falls
   * back to {@link #DEFAULT_NOISE_AMPLITUDE} when SNR is zero or negative.
   */
  static double estimateNoiseAmplitude(double meanSnr) {
    if (meanSnr <= 0.0) {
      return DEFAULT_NOISE_AMPLITUDE;
    }
    return Math.max(0.0, Math.min(1.0, 1.0 / (meanSnr + 1.0)));
  }

  private static double mean(
      List<WingbeatFeatureVector> vectors, ToDoubleFunction<WingbeatFeatureVector> extractor) {
    double sum = 0.0;
    for (WingbeatFeatureVector v : vectors) {
      sum += extractor.applyAsDouble(v);
    }
    return sum / vectors.size();
  }
}
