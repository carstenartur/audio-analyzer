package org.hammer.audio.experimental.acoustic.benchmark;

/** Placeholder metric for benchmark comparisons of recovered wingbeat frequencies. */
public record FrequencyErrorMetric(double absoluteErrorHz, double relativeError) {

  public FrequencyErrorMetric {
    if (!Double.isFinite(absoluteErrorHz) || absoluteErrorHz < 0.0) {
      throw new IllegalArgumentException("absoluteErrorHz must be finite and >= 0");
    }
    if (!Double.isFinite(relativeError) || relativeError < 0.0) {
      throw new IllegalArgumentException("relativeError must be finite and >= 0");
    }
  }
}
