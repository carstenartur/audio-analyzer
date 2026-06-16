package org.hammer.audio.experimental.acoustic.feature.evaluation;

import java.util.Objects;

/**
 * Evaluation summary for a single feature column in a labelled dataset.
 *
 * @param featureName human-readable feature name; must not be blank
 * @param statistics descriptive statistics over all samples; must not be {@code null}
 * @param separation class-separation analysis; must not be {@code null}
 * @param labelCorrelation maximum absolute point-biserial correlation between the feature values
 *     and the binary class-indicator for any class label; in {@code [0,1]}
 */
public record FeatureEvaluationEntry(
    String featureName,
    FeatureStatistics statistics,
    ClassSeparationScore separation,
    double labelCorrelation) {

  /** Validate fields. */
  public FeatureEvaluationEntry {
    Objects.requireNonNull(featureName, "featureName");
    Objects.requireNonNull(statistics, "statistics");
    Objects.requireNonNull(separation, "separation");
    if (featureName.isBlank()) {
      throw new IllegalArgumentException("featureName must not be blank");
    }
    if (!featureName.equals(statistics.featureName())) {
      throw new IllegalArgumentException("statistics.featureName must equal featureName");
    }
    if (!featureName.equals(separation.featureName())) {
      throw new IllegalArgumentException("separation.featureName must equal featureName");
    }
    if (!Double.isFinite(labelCorrelation) || labelCorrelation < 0.0 || labelCorrelation > 1.0) {
      throw new IllegalArgumentException("labelCorrelation must be finite and in [0,1]");
    }
  }
}
