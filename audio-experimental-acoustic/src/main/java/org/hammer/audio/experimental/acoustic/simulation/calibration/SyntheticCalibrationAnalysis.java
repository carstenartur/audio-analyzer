package org.hammer.audio.experimental.acoustic.simulation.calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import org.hammer.audio.experimental.acoustic.feature.comparison.SyntheticRealComparison;
import org.hammer.audio.experimental.acoustic.feature.comparison.SyntheticRealComparisonReport;
import org.hammer.audio.experimental.acoustic.simulation.WingbeatSignalParameters;
import org.hammer.audio.experimental.acoustic.wingbeat.WingbeatFeatureVector;

/**
 * Analyses the distribution difference between a synthetic generator configuration and real
 * wingbeat recordings.
 *
 * <p>Synthetic feature vectors are derived analytically from a {@link WingbeatSignalParameters}
 * instance — no full audio pipeline is required. Each synthetic vector captures the dominant
 * frequency (with jitter noise), harmonic profile, spectral centroid, spectral bandwidth,
 * modulation depth, frequency drift, jitter and an estimated SNR. The resulting corpus is compared
 * against the real corpus using {@link SyntheticRealComparison}.
 *
 * <p>This service is stateless and may be called concurrently.
 */
public final class SyntheticCalibrationAnalysis {

  /** Default number of synthetic vectors generated per call to {@link #analyse}. */
  static final int DEFAULT_SYNTHETIC_COUNT = 200;

  /** Default random seed for reproducible synthetic generation. */
  static final long DEFAULT_SEED = 0xCAFEBABECAFEBABEL;

  private final SyntheticRealComparison comparison;

  /** Create an analysis instance. */
  public SyntheticCalibrationAnalysis() {
    this.comparison = new SyntheticRealComparison();
  }

  /**
   * Analyse differences between synthetic vectors derived from {@code params} and the supplied real
   * corpus using the {@link SyntheticRealComparisonReport#DEFAULT_WEAKNESS_THRESHOLD default
   * weakness threshold}.
   *
   * <p>The synthetic corpus size matches {@code real.size()} for a fair statistical comparison.
   *
   * @param params generator parameters to evaluate; must not be {@code null}
   * @param real real recording feature vectors; must not be {@code null} or empty
   * @return comparison report; never {@code null}
   */
  public SyntheticRealComparisonReport analyse(
      WingbeatSignalParameters params, List<WingbeatFeatureVector> real) {
    Objects.requireNonNull(params, "params");
    Objects.requireNonNull(real, "real");
    if (real.isEmpty()) {
      throw new IllegalArgumentException("real corpus must not be empty");
    }
    List<WingbeatFeatureVector> synthetic =
        generateSyntheticVectors(params, real.size(), DEFAULT_SEED);
    return comparison.compare(synthetic, real);
  }

  /**
   * Generate {@code count} synthetic {@link WingbeatFeatureVector}s analytically from the given
   * generator parameters without running the full audio pipeline.
   *
   * <p>Each vector is derived from the parameter values:
   *
   * <ul>
   *   <li>Fundamental frequency is drawn from a uniform distribution centred on {@link
   *       WingbeatSignalParameters#fundamentalFrequencyHz()} with half-width {@link
   *       WingbeatSignalParameters#jitterHz()}.
   *   <li>Harmonic amplitudes and ratios are taken directly from the resolved amplitude list.
   *   <li>Spectral centroid and bandwidth are computed analytically from the harmonic profile.
   *   <li>SNR is estimated as {@code totalHarmonicAmplitude / noiseAmplitude} when noise is
   *       non-zero; {@code 0} otherwise.
   * </ul>
   *
   * @param params generator parameters; must not be {@code null}
   * @param count number of vectors to generate; must be {@code >= 1}
   * @param seed random seed for the jitter simulation
   * @return unmodifiable list of synthetic feature vectors; never {@code null}
   */
  public static List<WingbeatFeatureVector> generateSyntheticVectors(
      WingbeatSignalParameters params, int count, long seed) {
    Objects.requireNonNull(params, "params");
    if (count < 1) {
      throw new IllegalArgumentException("count must be >= 1");
    }
    Random rng = new Random(seed);
    List<Double> resolvedAmplitudes = params.resolvedHarmonicAmplitudes();
    List<Double> ratios = computeHarmonicRatios(resolvedAmplitudes);
    double centroid = computeSpectralCentroid(params.fundamentalFrequencyHz(), resolvedAmplitudes);
    double bandwidth =
        computeSpectralBandwidth(params.fundamentalFrequencyHz(), resolvedAmplitudes, centroid);
    double snr = estimateSnr(resolvedAmplitudes, params.noiseAmplitude());
    double jitter = params.jitterHz();

    List<WingbeatFeatureVector> vectors = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      double freqOffset = jitter > 0.0 ? (rng.nextDouble() * 2.0 - 1.0) * jitter : 0.0;
      double freq = Math.max(0.0, params.fundamentalFrequencyHz() + freqOffset);
      vectors.add(
          new WingbeatFeatureVector(
              freq,
              resolvedAmplitudes,
              ratios,
              centroid,
              bandwidth,
              params.driftHzPerSecond(),
              jitter,
              params.modulationDepth(),
              snr,
              1.0,
              1.0));
    }
    return List.copyOf(vectors);
  }

  private static List<Double> computeHarmonicRatios(List<Double> amplitudes) {
    if (amplitudes.size() < 2 || amplitudes.get(0) <= 0.0) {
      return List.of();
    }
    double fundamental = amplitudes.get(0);
    List<Double> ratios = new ArrayList<>(amplitudes.size() - 1);
    for (int i = 1; i < amplitudes.size(); i++) {
      ratios.add(amplitudes.get(i) / fundamental);
    }
    return List.copyOf(ratios);
  }

  private static double computeSpectralCentroid(double fundamentalHz, List<Double> amplitudes) {
    double weightedFreq = 0.0;
    double totalWeight = 0.0;
    for (int k = 0; k < amplitudes.size(); k++) {
      double freq = fundamentalHz * (k + 1);
      double amp = amplitudes.get(k);
      weightedFreq += freq * amp;
      totalWeight += amp;
    }
    return totalWeight > 0.0 ? weightedFreq / totalWeight : fundamentalHz;
  }

  private static double computeSpectralBandwidth(
      double fundamentalHz, List<Double> amplitudes, double centroidHz) {
    double weightedVariance = 0.0;
    double totalWeight = 0.0;
    for (int k = 0; k < amplitudes.size(); k++) {
      double freq = fundamentalHz * (k + 1);
      double amp = amplitudes.get(k);
      double deviation = freq - centroidHz;
      weightedVariance += deviation * deviation * amp;
      totalWeight += amp;
    }
    return totalWeight > 0.0 ? Math.sqrt(weightedVariance / totalWeight) : 0.0;
  }

  /**
   * Estimate SNR as {@code totalHarmonicAmplitude / noiseAmplitude}.
   *
   * @param amplitudes resolved harmonic amplitudes
   * @param noiseAmplitude additive noise amplitude from the generator parameters
   * @return estimated SNR, or {@code 0} when noise amplitude is zero or negative
   */
  static double estimateSnr(List<Double> amplitudes, double noiseAmplitude) {
    if (noiseAmplitude <= 0.0) {
      return 0.0;
    }
    double total = 0.0;
    for (double a : amplitudes) {
      total += a;
    }
    return total / noiseAmplitude;
  }
}
