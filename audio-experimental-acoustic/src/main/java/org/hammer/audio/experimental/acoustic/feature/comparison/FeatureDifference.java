package org.hammer.audio.experimental.acoustic.feature.comparison;

import java.util.Objects;

/**
 * Per-feature difference between the synthetic and real corpus means.
 *
 * @param featureName human-readable feature name; must not be blank
 * @param syntheticMean mean feature value in the synthetic corpus
 * @param realMean mean feature value in the real corpus
 * @param absoluteDifference {@code |realMean - syntheticMean|}; must be finite and {@code >= 0}
 * @param relativeDifference {@code absoluteDifference / |syntheticMean|}, or {@code 0} when the
 *     synthetic mean is zero; must be finite and {@code >= 0}
 * @param zScore standardised difference {@code (realMean - syntheticMean) / syntheticStdDev} when
 *     {@code syntheticStdDev > 0}, or {@code 0}; must be finite
 */
public record FeatureDifference(
    String featureName,
    double syntheticMean,
    double realMean,
    double absoluteDifference,
    double relativeDifference,
    double zScore) {

  /** Validate fields. */
  public FeatureDifference {
    Objects.requireNonNull(featureName, "featureName");
    if (featureName.isBlank()) {
      throw new IllegalArgumentException("featureName must not be blank");
    }
    if (!Double.isFinite(syntheticMean)) {
      throw new IllegalArgumentException("syntheticMean must be finite");
    }
    if (!Double.isFinite(realMean)) {
      throw new IllegalArgumentException("realMean must be finite");
    }
    if (!Double.isFinite(absoluteDifference) || absoluteDifference < 0.0) {
      throw new IllegalArgumentException("absoluteDifference must be finite and >= 0");
    }
    if (!Double.isFinite(relativeDifference) || relativeDifference < 0.0) {
      throw new IllegalArgumentException("relativeDifference must be finite and >= 0");
    }
    if (!Double.isFinite(zScore)) {
      throw new IllegalArgumentException("zScore must be finite");
    }
  }
}
