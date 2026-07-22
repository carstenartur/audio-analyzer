package org.hammer.audio.experimental.acoustic.benchmark.tdoa;

/**
 * Aggregated accuracy and confidence metrics for one TDOA strategy.
 *
 * @param algorithmName registered algorithm name
 * @param caseCount evaluated deterministic cases
 * @param meanAbsoluteErrorSamples mean absolute known-delay error in samples
 * @param maximumAbsoluteErrorSamples maximum absolute known-delay error in samples
 * @param meanConfidence mean estimator confidence
 * @param ambiguousCount cases marked ambiguous by a diagnostic estimator
 */
public record TdoaAlgorithmBenchmarkResult(
    String algorithmName,
    int caseCount,
    double meanAbsoluteErrorSamples,
    double maximumAbsoluteErrorSamples,
    double meanConfidence,
    int ambiguousCount) {

  // Validate one aggregate result.
  public TdoaAlgorithmBenchmarkResult {
    if (algorithmName == null || algorithmName.isBlank()) {
      throw new IllegalArgumentException("algorithmName must not be blank");
    }
    if (caseCount < 1) {
      throw new IllegalArgumentException("caseCount must be >= 1");
    }
    requireNonNegativeFinite(meanAbsoluteErrorSamples, "meanAbsoluteErrorSamples");
    requireNonNegativeFinite(maximumAbsoluteErrorSamples, "maximumAbsoluteErrorSamples");
    if (!Double.isFinite(meanConfidence) || meanConfidence < 0.0 || meanConfidence > 1.0) {
      throw new IllegalArgumentException("meanConfidence must be finite and in [0,1]");
    }
    if (ambiguousCount < 0 || ambiguousCount > caseCount) {
      throw new IllegalArgumentException("ambiguousCount must be in [0, caseCount]");
    }
  }

  private static void requireNonNegativeFinite(double value, String name) {
    if (Double.isFinite(value) && value >= 0.0) {
      return;
    }
    throw new IllegalArgumentException(name + " must be finite and >= 0");
  }
}
