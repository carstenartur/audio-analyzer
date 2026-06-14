package org.hammer.audio.experimental.acoustic.benchmark;

/**
 * Summary metric for benchmark comparisons of classification outputs against scenario truth.
 *
 * @param accuracy ratio of correct classifications among evaluated samples
 * @param correctCount number of correct classifications
 * @param sampleCount total classification sample count
 * @param evaluatedCount number of evaluated classification samples
 * @param skippedCount number of skipped classification samples
 * @param unavailableTruthCount number of samples without comparable truth labels
 */
public record ClassificationAccuracyMetric(
    Double accuracy,
    int correctCount,
    int sampleCount,
    int evaluatedCount,
    int skippedCount,
    int unavailableTruthCount) {

  public ClassificationAccuracyMetric {
    validateCounts(sampleCount, evaluatedCount, skippedCount, unavailableTruthCount);
    if (correctCount < 0 || correctCount > evaluatedCount) {
      throw new IllegalArgumentException("correctCount must be in [0, evaluatedCount]");
    }
    if (evaluatedCount == 0) {
      if (accuracy != null) {
        throw new IllegalArgumentException("accuracy must be null when evaluatedCount is 0");
      }
    } else if (accuracy == null || !Double.isFinite(accuracy) || accuracy < 0.0 || accuracy > 1.0) {
      throw new IllegalArgumentException("accuracy must be finite and in [0,1]");
    }
  }

  /** Build a metric from the number of correct and compared classifications. */
  public static ClassificationAccuracyMetric ofCounts(
      int correctCount, int evaluatedCount, int skippedCount, int unavailableTruthCount) {
    return new ClassificationAccuracyMetric(
        evaluatedCount == 0 ? null : correctCount / (double) evaluatedCount,
        correctCount,
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
}
