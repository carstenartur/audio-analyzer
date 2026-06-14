package org.hammer.audio.experimental.acoustic.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Summary metric for benchmark comparisons of localization accuracy.
 *
 * @param meanDistanceErrorMeters mean distance error in meters
 * @param medianDistanceErrorMeters median distance error in meters
 * @param meanAngularErrorDegrees mean angular error in degrees
 * @param medianAngularErrorDegrees median angular error in degrees
 * @param sampleCount total localization sample count
 * @param evaluatedCount number of evaluated localization samples
 * @param skippedCount number of skipped localization samples
 * @param unavailableTruthCount number of samples without usable localization truth
 */
public record LocalizationErrorMetric(
    Double meanDistanceErrorMeters,
    Double medianDistanceErrorMeters,
    Double meanAngularErrorDegrees,
    Double medianAngularErrorDegrees,
    int sampleCount,
    int evaluatedCount,
    int skippedCount,
    int unavailableTruthCount) {

  public LocalizationErrorMetric {
    validateCounts(sampleCount, evaluatedCount, skippedCount, unavailableTruthCount);
    validateMetric(meanDistanceErrorMeters, evaluatedCount, "meanDistanceErrorMeters");
    validateMetric(medianDistanceErrorMeters, evaluatedCount, "medianDistanceErrorMeters");
    validateMetric(meanAngularErrorDegrees, evaluatedCount, "meanAngularErrorDegrees");
    validateMetric(medianAngularErrorDegrees, evaluatedCount, "medianAngularErrorDegrees");
  }

  /** Build a summary metric from per-sample distance and angular errors. */
  public static LocalizationErrorMetric ofSamples(
      List<Double> distanceErrorsMeters,
      List<Double> angularErrorsDegrees,
      int skippedCount,
      int unavailableTruthCount) {
    Objects.requireNonNull(distanceErrorsMeters, "distanceErrorsMeters");
    Objects.requireNonNull(angularErrorsDegrees, "angularErrorsDegrees");
    if (distanceErrorsMeters.size() != angularErrorsDegrees.size()) {
      throw new IllegalArgumentException(
          "distanceErrorsMeters and angularErrorsDegrees must have the same size");
    }
    int evaluatedCount = distanceErrorsMeters.size();
    return new LocalizationErrorMetric(
        evaluatedCount == 0 ? null : mean(distanceErrorsMeters),
        evaluatedCount == 0 ? null : median(distanceErrorsMeters),
        evaluatedCount == 0 ? null : mean(angularErrorsDegrees),
        evaluatedCount == 0 ? null : median(angularErrorsDegrees),
        evaluatedCount + skippedCount + unavailableTruthCount,
        evaluatedCount,
        skippedCount,
        unavailableTruthCount);
  }

  private static void validateCounts(
      int sampleCount, int evaluatedCount, int skippedCount, int unavailableTruthCount) {
    if (sampleCount < 0 || evaluatedCount < 0 || skippedCount < 0 || unavailableTruthCount < 0) {
      throw new IllegalArgumentException("metric counts must be >= 0");
    }
    if (sampleCount != evaluatedCount + skippedCount + unavailableTruthCount) {
      throw new IllegalArgumentException(
          "sampleCount must equal evaluatedCount + skippedCount + unavailableTruthCount");
    }
  }

  private static void validateMetric(Double value, int evaluatedCount, String fieldName) {
    if (evaluatedCount == 0) {
      if (value != null) {
        throw new IllegalArgumentException(fieldName + " must be null when evaluatedCount is 0");
      }
      return;
    }
    if (value == null || !Double.isFinite(value) || value < 0.0) {
      throw new IllegalArgumentException(fieldName + " must be finite and >= 0");
    }
  }

  private static double mean(List<Double> values) {
    double sum = 0.0;
    for (Double value : values) {
      if (value == null || !Double.isFinite(value) || value < 0.0) {
        throw new IllegalArgumentException("metric samples must be finite and >= 0");
      }
      sum += value;
    }
    return sum / values.size();
  }

  private static double median(List<Double> values) {
    List<Double> sorted = new ArrayList<>(values.size());
    for (Double value : values) {
      if (value == null || !Double.isFinite(value) || value < 0.0) {
        throw new IllegalArgumentException("metric samples must be finite and >= 0");
      }
      sorted.add(value);
    }
    Collections.sort(sorted);
    int middle = sorted.size() / 2;
    if ((sorted.size() & 1) == 1) {
      return sorted.get(middle);
    }
    return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
  }
}
