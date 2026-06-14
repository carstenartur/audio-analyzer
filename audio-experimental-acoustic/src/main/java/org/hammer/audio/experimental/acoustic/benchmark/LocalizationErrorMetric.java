package org.hammer.audio.experimental.acoustic.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Summary metric for benchmark comparisons of localization accuracy. */
public record LocalizationErrorMetric(
    double meanDistanceErrorMeters,
    double medianDistanceErrorMeters,
    double meanAngularErrorDegrees,
    double medianAngularErrorDegrees,
    int sampleCount) {

  public LocalizationErrorMetric {
    if (!Double.isFinite(meanDistanceErrorMeters) || meanDistanceErrorMeters < 0.0) {
      throw new IllegalArgumentException("meanDistanceErrorMeters must be finite and >= 0");
    }
    if (!Double.isFinite(medianDistanceErrorMeters) || medianDistanceErrorMeters < 0.0) {
      throw new IllegalArgumentException("medianDistanceErrorMeters must be finite and >= 0");
    }
    if (!Double.isFinite(meanAngularErrorDegrees) || meanAngularErrorDegrees < 0.0) {
      throw new IllegalArgumentException("meanAngularErrorDegrees must be finite and >= 0");
    }
    if (!Double.isFinite(medianAngularErrorDegrees) || medianAngularErrorDegrees < 0.0) {
      throw new IllegalArgumentException("medianAngularErrorDegrees must be finite and >= 0");
    }
    if (sampleCount < 0) {
      throw new IllegalArgumentException("sampleCount must be >= 0");
    }
  }

  /** Empty metric for scenarios where no aligned localization samples were available. */
  public static LocalizationErrorMetric empty() {
    return new LocalizationErrorMetric(0.0, 0.0, 0.0, 0.0, 0);
  }

  /** Build a summary metric from per-sample distance and angular errors. */
  public static LocalizationErrorMetric ofSamples(
      List<Double> distanceErrorsMeters, List<Double> angularErrorsDegrees) {
    Objects.requireNonNull(distanceErrorsMeters, "distanceErrorsMeters");
    Objects.requireNonNull(angularErrorsDegrees, "angularErrorsDegrees");
    if (distanceErrorsMeters.size() != angularErrorsDegrees.size()) {
      throw new IllegalArgumentException(
          "distanceErrorsMeters and angularErrorsDegrees must have the same size");
    }
    if (distanceErrorsMeters.isEmpty()) {
      return empty();
    }
    return new LocalizationErrorMetric(
        mean(distanceErrorsMeters),
        median(distanceErrorsMeters),
        mean(angularErrorsDegrees),
        median(angularErrorsDegrees),
        distanceErrorsMeters.size());
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
