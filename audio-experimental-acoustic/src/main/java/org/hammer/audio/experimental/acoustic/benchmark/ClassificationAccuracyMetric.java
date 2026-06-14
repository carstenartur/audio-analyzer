package org.hammer.audio.experimental.acoustic.benchmark;

/** Summary metric for benchmark comparisons of classification outputs against scenario truth. */
public record ClassificationAccuracyMetric(double accuracy, int correctCount, int comparedCount) {

  public ClassificationAccuracyMetric {
    if (!Double.isFinite(accuracy) || accuracy < 0.0 || accuracy > 1.0) {
      throw new IllegalArgumentException("accuracy must be finite and in [0,1]");
    }
    if (correctCount < 0) {
      throw new IllegalArgumentException("correctCount must be >= 0");
    }
    if (comparedCount < 0) {
      throw new IllegalArgumentException("comparedCount must be >= 0");
    }
    if (correctCount > comparedCount) {
      throw new IllegalArgumentException("correctCount must be <= comparedCount");
    }
  }

  /** Empty metric for scenarios where no classification outputs were available. */
  public static ClassificationAccuracyMetric empty() {
    return new ClassificationAccuracyMetric(0.0, 0, 0);
  }

  /** Build a metric from the number of correct and compared classifications. */
  public static ClassificationAccuracyMetric ofCounts(int correctCount, int comparedCount) {
    if (comparedCount == 0) {
      return empty();
    }
    return new ClassificationAccuracyMetric(
        correctCount / (double) comparedCount, correctCount, comparedCount);
  }
}
