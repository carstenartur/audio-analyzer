package org.hammer.audio.experimental.acoustic.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Summary metric for benchmark comparisons of recovered source frequencies. */
public record FrequencyErrorMetric(
    Double meanAbsoluteErrorHz,
    Double medianAbsoluteErrorHz,
    Double meanRelativeError,
    int sampleCount,
    int evaluatedCount,
    int skippedCount,
    int unavailableTruthCount) {

  public FrequencyErrorMetric {
    validateCounts(sampleCount, evaluatedCount, skippedCount, unavailableTruthCount);
    validateMetric(meanAbsoluteErrorHz, evaluatedCount, "meanAbsoluteErrorHz");
    validateMetric(medianAbsoluteErrorHz, evaluatedCount, "medianAbsoluteErrorHz");
    validateMetric(meanRelativeError, evaluatedCount, "meanRelativeError");
  }

  /** Build a summary metric from per-sample absolute and relative errors. */
  public static FrequencyErrorMetric ofSamples(
      List<Double> absoluteErrorsHz,
      List<Double> relativeErrors,
      int skippedCount,
      int unavailableTruthCount) {
    Objects.requireNonNull(absoluteErrorsHz, "absoluteErrorsHz");
    Objects.requireNonNull(relativeErrors, "relativeErrors");
    if (absoluteErrorsHz.size() != relativeErrors.size()) {
      throw new IllegalArgumentException(
          "absoluteErrorsHz and relativeErrors must have the same size");
    }
    int evaluatedCount = absoluteErrorsHz.size();
    return new FrequencyErrorMetric(
        evaluatedCount == 0 ? null : mean(absoluteErrorsHz),
        evaluatedCount == 0 ? null : median(absoluteErrorsHz),
        evaluatedCount == 0 ? null : mean(relativeErrors),
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
