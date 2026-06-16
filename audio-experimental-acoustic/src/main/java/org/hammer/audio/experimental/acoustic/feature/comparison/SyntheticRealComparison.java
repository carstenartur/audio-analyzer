package org.hammer.audio.experimental.acoustic.feature.comparison;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToDoubleFunction;
import org.hammer.audio.experimental.acoustic.wingbeat.WingbeatFeatureVector;

/**
 * Compares feature distributions between a synthetic and a real corpus of {@link
 * WingbeatFeatureVector}s.
 *
 * <p>For every scalar feature the service computes the per-corpus mean and standard deviation and
 * derives absolute difference, relative difference and a z-score. Features covering dominant
 * frequency, harmonics, duration, SNR and modulation are compared directly from the available
 * {@link WingbeatFeatureVector} fields.
 *
 * <p>This service is stateless and may be called concurrently.
 */
public final class SyntheticRealComparison {

  private static final List<Map.Entry<String, ToDoubleFunction<WingbeatFeatureVector>>> EXTRACTORS =
      buildExtractors();

  private static List<Map.Entry<String, ToDoubleFunction<WingbeatFeatureVector>>>
      buildExtractors() {
    List<Map.Entry<String, ToDoubleFunction<WingbeatFeatureVector>>> list = new ArrayList<>();
    list.add(Map.entry("fundamentalFrequencyHz", WingbeatFeatureVector::fundamentalFrequencyHz));
    list.add(
        Map.entry(
            "harmonicAmplitude1",
            v -> v.harmonicAmplitudes().isEmpty() ? 0.0 : v.harmonicAmplitudes().get(0)));
    list.add(
        Map.entry(
            "harmonicRatio1", v -> v.harmonicRatios().isEmpty() ? 0.0 : v.harmonicRatios().get(0)));
    list.add(Map.entry("trackDurationSeconds", WingbeatFeatureVector::trackDurationSeconds));
    list.add(Map.entry("signalToNoiseRatio", WingbeatFeatureVector::signalToNoiseRatio));
    list.add(Map.entry("amplitudeModulation", WingbeatFeatureVector::amplitudeModulation));
    list.add(Map.entry("spectralCentroidHz", WingbeatFeatureVector::spectralCentroidHz));
    list.add(Map.entry("spectralBandwidthHz", WingbeatFeatureVector::spectralBandwidthHz));
    return List.copyOf(list);
  }

  /**
   * Compare synthetic and real corpora using the {@link
   * SyntheticRealComparisonReport#DEFAULT_WEAKNESS_THRESHOLD default weakness threshold}.
   *
   * @param synthetic synthetic corpus; must not be {@code null} or empty
   * @param real real corpus; must not be {@code null} or empty
   * @return comparison report; never {@code null}
   */
  public SyntheticRealComparisonReport compare(
      List<WingbeatFeatureVector> synthetic, List<WingbeatFeatureVector> real) {
    return compare(synthetic, real, SyntheticRealComparisonReport.DEFAULT_WEAKNESS_THRESHOLD);
  }

  /**
   * Compare synthetic and real corpora with a configurable weakness threshold.
   *
   * @param synthetic synthetic corpus; must not be {@code null} or empty
   * @param real real corpus; must not be {@code null} or empty
   * @param weaknessThreshold relative-difference threshold for flagging weaknesses; must be {@code
   *     > 0}
   * @return comparison report; never {@code null}
   */
  public SyntheticRealComparisonReport compare(
      List<WingbeatFeatureVector> synthetic,
      List<WingbeatFeatureVector> real,
      double weaknessThreshold) {
    Objects.requireNonNull(synthetic, "synthetic");
    Objects.requireNonNull(real, "real");
    if (synthetic.isEmpty()) {
      throw new IllegalArgumentException("synthetic corpus must not be empty");
    }
    if (real.isEmpty()) {
      throw new IllegalArgumentException("real corpus must not be empty");
    }

    List<FeatureDifference> differences = new ArrayList<>(EXTRACTORS.size());
    for (Map.Entry<String, ToDoubleFunction<WingbeatFeatureVector>> fe : EXTRACTORS) {
      double[] synthValues = extract(synthetic, fe.getValue());
      double[] realValues = extract(real, fe.getValue());
      differences.add(computeDifference(fe.getKey(), synthValues, realValues));
    }
    return new SyntheticRealComparisonReport(differences, weaknessThreshold);
  }

  private static double[] extract(
      List<WingbeatFeatureVector> vectors, ToDoubleFunction<WingbeatFeatureVector> extractor) {
    double[] values = new double[vectors.size()];
    for (int i = 0; i < vectors.size(); i++) {
      values[i] = extractor.applyAsDouble(vectors.get(i));
    }
    return values;
  }

  private static FeatureDifference computeDifference(
      String name, double[] synthValues, double[] realValues) {
    double synthMean = mean(synthValues);
    double realMean = mean(realValues);
    double synthStd = stdDev(synthValues, synthMean);

    double absDiff = Math.abs(realMean - synthMean);
    double relDiff = synthMean == 0.0 ? 0.0 : absDiff / Math.abs(synthMean);
    double zScore = synthStd == 0.0 ? 0.0 : (realMean - synthMean) / synthStd;

    return new FeatureDifference(name, synthMean, realMean, absDiff, relDiff, zScore);
  }

  private static double mean(double[] values) {
    double sum = 0.0;
    for (double v : values) {
      sum += v;
    }
    return sum / values.length;
  }

  private static double stdDev(double[] values, double mean) {
    double variance = 0.0;
    for (double v : values) {
      double diff = v - mean;
      variance += diff * diff;
    }
    return Math.sqrt(variance / values.length);
  }
}
