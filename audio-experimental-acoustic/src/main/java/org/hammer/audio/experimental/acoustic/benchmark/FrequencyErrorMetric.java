package org.hammer.audio.experimental.acoustic.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Summary metric for benchmark comparisons of recovered source frequencies. */
public record FrequencyErrorMetric(
    double meanAbsoluteErrorHz,
    double medianAbsoluteErrorHz,
    double meanRelativeError,
    int sampleCount) {

  public FrequencyErrorMetric {
    if (!Double.isFinite(meanAbsoluteErrorHz) || meanAbsoluteErrorHz < 0.0) {
      throw new IllegalArgumentException("meanAbsoluteErrorHz must be finite and >= 0");
    }
    if (!Double.isFinite(medianAbsoluteErrorHz) || medianAbsoluteErrorHz < 0.0) {
      throw new IllegalArgumentException("medianAbsoluteErrorHz must be finite and >= 0");
    }
    if (!Double.isFinite(meanRelativeError) || meanRelativeError < 0.0) {
      throw new IllegalArgumentException("meanRelativeError must be finite and >= 0");
    }
    if (sampleCount < 0) {
      throw new IllegalArgumentException("sampleCount must be >= 0");
    }
  }

  /** Empty metric for scenarios where no aligned frequency samples were available. */
  public static FrequencyErrorMetric empty() {
    return new FrequencyErrorMetric(0.0, 0.0, 0.0, 0);
  }

  /** Build a summary metric from per-sample absolute and relative errors. */
  public static FrequencyErrorMetric ofSamples(
      List<Double> absoluteErrorsHz, List<Double> relativeErrors) {
    Objects.requireNonNull(absoluteErrorsHz, "absoluteErrorsHz");
    Objects.requireNonNull(relativeErrors, "relativeErrors");
    if (absoluteErrorsHz.size() != relativeErrors.size()) {
      throw new IllegalArgumentException(
          "absoluteErrorsHz and relativeErrors must have the same size");
    }
    if (absoluteErrorsHz.isEmpty()) {
      return empty();
    }
    return new FrequencyErrorMetric(
        mean(absoluteErrorsHz), median(absoluteErrorsHz), mean(relativeErrors), absoluteErrorsHz.size());
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
