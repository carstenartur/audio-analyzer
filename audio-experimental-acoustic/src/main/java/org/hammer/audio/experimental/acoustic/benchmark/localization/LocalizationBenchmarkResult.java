package org.hammer.audio.experimental.acoustic.benchmark.localization;

import java.util.Objects;

/**
 * Result of running a {@link LocalizationBenchmark} on one simulation scenario.
 *
 * @param scenarioId the identifier of the scenario that was benchmarked; must not be blank
 * @param meanLocalizationErrorMeters mean distance error between estimated and ground-truth
 *     positions across all matched snapshot frames; {@code 0} when no frames were evaluated
 * @param trackingError mean track-continuity error in {@code [0,1]}: fraction of frames where the
 *     dominant track identity changed unexpectedly; {@code 0} when not applicable
 * @param falsePositiveCount number of spurious (unmatched) tracks accumulated across all frames;
 *     must be {@code >= 0}
 * @param falseNegativeCount number of expected sources missed across all frames; must be {@code >=
 *     0}
 * @param evaluatedFrameCount number of snapshot frames that were compared against ground truth;
 *     must be {@code >= 0}
 */
public record LocalizationBenchmarkResult(
    String scenarioId,
    double meanLocalizationErrorMeters,
    double trackingError,
    int falsePositiveCount,
    int falseNegativeCount,
    int evaluatedFrameCount) {

  /** Validate fields. */
  public LocalizationBenchmarkResult {
    Objects.requireNonNull(scenarioId, "scenarioId");
    if (scenarioId.isBlank()) {
      throw new IllegalArgumentException("scenarioId must not be blank");
    }
    if (!Double.isFinite(meanLocalizationErrorMeters) || meanLocalizationErrorMeters < 0.0) {
      throw new IllegalArgumentException("meanLocalizationErrorMeters must be finite and >= 0");
    }
    if (!Double.isFinite(trackingError) || trackingError < 0.0 || trackingError > 1.0) {
      throw new IllegalArgumentException("trackingError must be finite and in [0,1]");
    }
    if (falsePositiveCount < 0) {
      throw new IllegalArgumentException("falsePositiveCount must be >= 0");
    }
    if (falseNegativeCount < 0) {
      throw new IllegalArgumentException("falseNegativeCount must be >= 0");
    }
    if (evaluatedFrameCount < 0) {
      throw new IllegalArgumentException("evaluatedFrameCount must be >= 0");
    }
  }
}
