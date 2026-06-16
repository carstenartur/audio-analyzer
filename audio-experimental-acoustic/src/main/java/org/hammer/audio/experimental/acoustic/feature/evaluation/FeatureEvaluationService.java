package org.hammer.audio.experimental.acoustic.feature.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import org.hammer.audio.experimental.acoustic.dataset.FeatureHistogram;
import org.hammer.audio.experimental.acoustic.wingbeat.WingbeatFeatureVector;

/**
 * Computes a {@link FeatureEvaluationReport} from a labelled collection of {@link
 * WingbeatFeatureVector}s.
 *
 * <p>Each scalar feature field of {@link WingbeatFeatureVector} becomes one {@link
 * FeatureEvaluationEntry}. List-valued fields ({@code harmonicAmplitudes} and {@code
 * harmonicRatios}) contribute their first element as a scalar feature, or {@code 0} when the list
 * is empty.
 *
 * <p>This service is stateless; the same instance may be called concurrently.
 */
public final class FeatureEvaluationService {

  /** Ordered list of (featureName, extractor) pairs covering all scalar-like fields. */
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
    list.add(Map.entry("spectralCentroidHz", WingbeatFeatureVector::spectralCentroidHz));
    list.add(Map.entry("spectralBandwidthHz", WingbeatFeatureVector::spectralBandwidthHz));
    list.add(
        Map.entry("frequencyDriftHzPerSecond", WingbeatFeatureVector::frequencyDriftHzPerSecond));
    list.add(Map.entry("frequencyJitterHz", WingbeatFeatureVector::frequencyJitterHz));
    list.add(Map.entry("amplitudeModulation", WingbeatFeatureVector::amplitudeModulation));
    list.add(Map.entry("signalToNoiseRatio", WingbeatFeatureVector::signalToNoiseRatio));
    list.add(Map.entry("trackDurationSeconds", WingbeatFeatureVector::trackDurationSeconds));
    list.add(Map.entry("featureConfidence", WingbeatFeatureVector::featureConfidence));
    return List.copyOf(list);
  }

  /**
   * Evaluate every feature in the given labelled dataset.
   *
   * @param vectors feature vectors; must not be {@code null} or empty
   * @param labels class labels, one per vector; must not be {@code null}; must have the same size
   *     as {@code vectors}
   * @return feature evaluation report; never {@code null}
   */
  public FeatureEvaluationReport evaluate(
      List<WingbeatFeatureVector> vectors, List<String> labels) {
    Objects.requireNonNull(vectors, "vectors");
    Objects.requireNonNull(labels, "labels");
    if (vectors.isEmpty()) {
      throw new IllegalArgumentException("vectors must not be empty");
    }
    if (vectors.size() != labels.size()) {
      throw new IllegalArgumentException("vectors and labels must have the same size");
    }

    List<FeatureEvaluationEntry> entries = new ArrayList<>(EXTRACTORS.size());
    for (Map.Entry<String, ToDoubleFunction<WingbeatFeatureVector>> fe : EXTRACTORS) {
      entries.add(evaluateFeature(fe.getKey(), fe.getValue(), vectors, labels));
    }
    return new FeatureEvaluationReport(entries);
  }

  /**
   * Return the ordered list of feature names that this service evaluates.
   *
   * @return feature names in evaluation order; never {@code null}
   */
  public static List<String> featureNames() {
    List<String> names = new ArrayList<>(EXTRACTORS.size());
    for (Map.Entry<String, ToDoubleFunction<WingbeatFeatureVector>> fe : EXTRACTORS) {
      names.add(fe.getKey());
    }
    return List.copyOf(names);
  }

  // -------------------------------------------------------------------------
  // Internal helpers
  // -------------------------------------------------------------------------

  private static FeatureEvaluationEntry evaluateFeature(
      String name,
      ToDoubleFunction<WingbeatFeatureVector> extractor,
      List<WingbeatFeatureVector> vectors,
      List<String> labels) {

    int n = vectors.size();
    double[] values = new double[n];
    for (int i = 0; i < n; i++) {
      values[i] = extractor.applyAsDouble(vectors.get(i));
    }

    FeatureStatistics stats = computeStatistics(name, values);
    ClassSeparationScore separation = computeSeparation(name, values, labels);
    double correlation = computeLabelCorrelation(values, labels);
    return new FeatureEvaluationEntry(name, stats, separation, correlation);
  }

  private static FeatureStatistics computeStatistics(String name, double[] values) {
    double sum = 0.0;
    int missingCount = 0;
    int observedCount = 0;
    List<Double> observedValues = new ArrayList<>(values.length);
    for (double v : values) {
      if (!Double.isFinite(v)) {
        missingCount++;
        continue;
      }
      sum += v;
      observedCount++;
      observedValues.add(v);
    }
    if (observedCount == 0) {
      return new FeatureStatistics(
          name, 0.0, 0.0, FeatureHistogram.of(name, new double[0]), missingCount);
    }
    double mean = sum / observedCount;
    double variance = 0.0;
    for (double v : observedValues) {
      double diff = v - mean;
      variance += diff * diff;
    }
    double stdDev = Math.sqrt(variance / observedCount);
    double[] observedArray = new double[observedValues.size()];
    for (int i = 0; i < observedValues.size(); i++) {
      observedArray[i] = observedValues.get(i);
    }
    FeatureHistogram histogram = FeatureHistogram.of(name, observedArray);
    return new FeatureStatistics(name, mean, stdDev, histogram, missingCount);
  }

  @SuppressWarnings("PMD.UseConcurrentHashMap")
  private static ClassSeparationScore computeSeparation(
      String name, double[] values, List<String> labels) {

    // Collect per-class sums and sum-of-squares
    Map<String, List<Double>> perClass = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i++) {
      perClass.computeIfAbsent(labels.get(i), k -> new ArrayList<>()).add(values[i]);
    }

    Map<String, Double> classMeans = new LinkedHashMap<>();
    Map<String, Double> classStdDevs = new LinkedHashMap<>();
    Map<String, Integer> classCounts = new LinkedHashMap<>();

    for (Map.Entry<String, List<Double>> entry : perClass.entrySet()) {
      List<Double> classValues = entry.getValue();
      double classSum = 0.0;
      for (double v : classValues) {
        classSum += v;
      }
      double classMean = classSum / classValues.size();
      double classVar = 0.0;
      for (double v : classValues) {
        double diff = v - classMean;
        classVar += diff * diff;
      }
      classVar /= classValues.size();
      classMeans.put(entry.getKey(), classMean);
      classStdDevs.put(entry.getKey(), Math.sqrt(classVar));
      classCounts.put(entry.getKey(), classValues.size());
    }

    // Global mean
    double globalMean = 0.0;
    for (double v : values) {
      globalMean += v;
    }
    globalMean /= values.length;

    // Between-class variance (weighted by class size)
    double betweenClassVar = 0.0;
    for (Map.Entry<String, Double> entry : classMeans.entrySet()) {
      int count = classCounts.get(entry.getKey());
      double diff = entry.getValue() - globalMean;
      betweenClassVar += count * diff * diff;
    }
    betweenClassVar /= values.length;

    // Within-class variance (weighted average of per-class variances)
    double withinClassVar = 0.0;
    for (Map.Entry<String, Double> entry : classStdDevs.entrySet()) {
      int count = classCounts.get(entry.getKey());
      double std = entry.getValue();
      withinClassVar += count * std * std;
    }
    withinClassVar /= values.length;

    double fisherRatio = withinClassVar == 0.0 ? 0.0 : betweenClassVar / withinClassVar;

    return new ClassSeparationScore(
        name, classMeans, classStdDevs, classCounts, betweenClassVar, withinClassVar, fisherRatio);
  }

  /**
   * Compute the maximum absolute point-biserial correlation between the feature values and any
   * binary class indicator.
   */
  private static double computeLabelCorrelation(double[] values, List<String> labels) {
    // Collect all distinct labels
    Set<String> distinctLabels = new LinkedHashSet<>(labels);
    if (distinctLabels.size() <= 1) {
      return 0.0;
    }

    double featureMean = 0.0;
    for (double v : values) {
      featureMean += v;
    }
    featureMean /= values.length;

    double featureStd = 0.0;
    for (double v : values) {
      double diff = v - featureMean;
      featureStd += diff * diff;
    }
    featureStd = Math.sqrt(featureStd / values.length);

    if (featureStd == 0.0) {
      return 0.0;
    }

    double maxAbsCorr = 0.0;
    for (String targetLabel : distinctLabels) {
      double corr = pointBiserialCorrelation(values, labels, targetLabel, featureMean, featureStd);
      double absCorr = Math.abs(corr);
      if (absCorr > maxAbsCorr) {
        maxAbsCorr = absCorr;
      }
    }
    return Math.min(1.0, maxAbsCorr);
  }

  /**
   * Point-biserial correlation between a continuous feature and a binary indicator for {@code
   * targetLabel}.
   */
  private static double pointBiserialCorrelation(
      double[] values,
      List<String> labels,
      String targetLabel,
      double featureMean,
      double featureStd) {

    int n = values.length;
    double sum1 = 0.0;
    int n1 = 0;
    for (int i = 0; i < n; i++) {
      if (targetLabel.equals(labels.get(i))) {
        sum1 += values[i];
        n1++;
      }
    }
    if (n1 == 0 || n1 == n) {
      return 0.0;
    }
    int n0 = n - n1;
    double mean1 = sum1 / n1;
    double mean0 = (featureMean * n - sum1) / n0;
    return ((mean1 - mean0) / featureStd) * Math.sqrt((double) n1 * n0 / (n * n));
  }
}
