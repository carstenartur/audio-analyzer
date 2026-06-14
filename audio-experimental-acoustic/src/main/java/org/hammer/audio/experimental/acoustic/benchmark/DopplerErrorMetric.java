package org.hammer.audio.experimental.acoustic.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Summary metric for benchmark comparisons of Doppler/radial-velocity estimates. */
public record DopplerErrorMetric(
    double meanAbsoluteErrorMetersPerSecond,
    double medianAbsoluteErrorMetersPerSecond,
    int sampleCount) {

  public DopplerErrorMetric {
    if (!Double.isFinite(meanAbsoluteErrorMetersPerSecond)
        || meanAbsoluteErrorMetersPerSecond < 0.0) {
      throw new IllegalArgumentException(
          "meanAbsoluteErrorMetersPerSecond must be finite and >= 0");
    }
    if (!Double.isFinite(medianAbsoluteErrorMetersPerSecond)
        || medianAbsoluteErrorMetersPerSecond < 0.0) {
      throw new IllegalArgumentException(
          "medianAbsoluteErrorMetersPerSecond must be finite and >= 0");
    }
    if (sampleCount < 0) {
      throw new IllegalArgumentException("sampleCount must be >= 0");
    }
  }

  /** Empty metric for scenarios where no aligned Doppler samples were available. */
  public static DopplerErrorMetric empty() {
    return new DopplerErrorMetric(0.0, 0.0, 0);
  }

  /** Build a summary metric from per-sample absolute errors. */
  public static DopplerErrorMetric ofSamples(List<Double> absoluteErrorsMetersPerSecond) {
    if (absoluteErrorsMetersPerSecond == null || absoluteErrorsMetersPerSecond.isEmpty()) {
      return empty();
    }
    return new DopplerErrorMetric(
        mean(absoluteErrorsMetersPerSecond),
        median(absoluteErrorsMetersPerSecond),
        absoluteErrorsMetersPerSecond.size());
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
