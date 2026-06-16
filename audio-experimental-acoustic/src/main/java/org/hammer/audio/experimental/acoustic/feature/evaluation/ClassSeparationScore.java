package org.hammer.audio.experimental.acoustic.feature.evaluation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Class-separation statistics for a single feature.
 *
 * <p>Captures per-class descriptive statistics and derived separation measures that estimate how
 * well the feature discriminates between class labels.
 *
 * @param featureName human-readable feature name; must not be blank
 * @param classMeans mean feature value per class label
 * @param classStdDevs population standard deviation of the feature per class label
 * @param classCounts number of samples per class label
 * @param betweenClassVariance variance of class means weighted by class size around the global mean
 * @param withinClassVariance mean within-class variance weighted by class size
 * @param fisherRatio Fisher discriminant ratio ({@code betweenClassVariance /
 *     withinClassVariance}); {@code 0} when {@code withinClassVariance} is zero
 */
public record ClassSeparationScore(
    String featureName,
    Map<String, Double> classMeans,
    Map<String, Double> classStdDevs,
    Map<String, Integer> classCounts,
    double betweenClassVariance,
    double withinClassVariance,
    double fisherRatio) {

  /** Validate and defensively copy fields. */
  public ClassSeparationScore {
    Objects.requireNonNull(featureName, "featureName");
    Objects.requireNonNull(classMeans, "classMeans");
    Objects.requireNonNull(classStdDevs, "classStdDevs");
    Objects.requireNonNull(classCounts, "classCounts");
    if (featureName.isBlank()) {
      throw new IllegalArgumentException("featureName must not be blank");
    }
    if (!Double.isFinite(betweenClassVariance) || betweenClassVariance < 0.0) {
      throw new IllegalArgumentException("betweenClassVariance must be finite and >= 0");
    }
    if (!Double.isFinite(withinClassVariance) || withinClassVariance < 0.0) {
      throw new IllegalArgumentException("withinClassVariance must be finite and >= 0");
    }
    if (!Double.isFinite(fisherRatio) || fisherRatio < 0.0) {
      throw new IllegalArgumentException("fisherRatio must be finite and >= 0");
    }
    classMeans = Collections.unmodifiableMap(new LinkedHashMap<>(classMeans));
    classStdDevs = Collections.unmodifiableMap(new LinkedHashMap<>(classStdDevs));
    classCounts = Collections.unmodifiableMap(new LinkedHashMap<>(classCounts));
  }
}
