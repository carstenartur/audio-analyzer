package org.hammer.audio.experimental.acoustic.benchmark;

/** Placeholder metric for benchmark comparisons of localization accuracy. */
public record LocalizationErrorMetric(double distanceErrorMeters) {

  public LocalizationErrorMetric {
    if (!Double.isFinite(distanceErrorMeters) || distanceErrorMeters < 0.0) {
      throw new IllegalArgumentException("distanceErrorMeters must be finite and >= 0");
    }
  }
}
