package org.hammer.audio.experimental.acoustic.feature.evaluation;

import java.util.Objects;
import org.hammer.audio.experimental.acoustic.dataset.FeatureHistogram;

/**
 * Descriptive statistics for a single extracted feature over a corpus of samples.
 *
 * @param featureName human-readable feature name; must not be blank
 * @param mean arithmetic mean of the observed values
 * @param stdDev population standard deviation of the observed values; {@code 0} for a single sample
 * @param histogram histogram of the observed values
 * @param missingCount number of samples for which the feature value was treated as missing (i.e.
 *     equal to exactly {@code 0} when no audio data was available); always {@code >= 0}
 */
public record FeatureStatistics(
    String featureName, double mean, double stdDev, FeatureHistogram histogram, int missingCount) {

  /** Validate fields. */
  public FeatureStatistics {
    Objects.requireNonNull(featureName, "featureName");
    Objects.requireNonNull(histogram, "histogram");
    if (featureName.isBlank()) {
      throw new IllegalArgumentException("featureName must not be blank");
    }
    if (!Double.isFinite(mean)) {
      throw new IllegalArgumentException("mean must be finite");
    }
    if (!Double.isFinite(stdDev) || stdDev < 0.0) {
      throw new IllegalArgumentException("stdDev must be finite and >= 0");
    }
    if (missingCount < 0) {
      throw new IllegalArgumentException("missingCount must be >= 0");
    }
  }
}
