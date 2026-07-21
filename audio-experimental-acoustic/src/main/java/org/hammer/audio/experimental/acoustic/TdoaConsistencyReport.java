package org.hammer.audio.experimental.acoustic;

import java.util.List;
import java.util.Objects;

/**
 * Aggregate physical and cycle-consistency evidence for pairwise TDOA estimates.
 *
 * @param findings ordered threshold violations
 * @param evaluatedCycles number of complete microphone triples checked
 * @param meanAbsoluteCycleResidualSeconds mean absolute residual over all complete triples
 * @param maximumAbsoluteCycleResidualSeconds maximum absolute residual over all complete triples
 * @param physicalViolationCount pair delays outside acoustic geometry bounds
 * @param consistencyScore normalized confidence multiplier in {@code [0,1]}
 */
public record TdoaConsistencyReport(
    List<TdoaConsistencyFinding> findings,
    int evaluatedCycles,
    double meanAbsoluteCycleResidualSeconds,
    double maximumAbsoluteCycleResidualSeconds,
    int physicalViolationCount,
    double consistencyScore) {

  // Validate and defensively copy one report.
  public TdoaConsistencyReport {
    findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
    if (evaluatedCycles < 0 || physicalViolationCount < 0) {
      throw new IllegalArgumentException("report counts must be >= 0");
    }
    requireNonNegativeFinite(
        meanAbsoluteCycleResidualSeconds, "meanAbsoluteCycleResidualSeconds");
    requireNonNegativeFinite(
        maximumAbsoluteCycleResidualSeconds, "maximumAbsoluteCycleResidualSeconds");
    if (!Double.isFinite(consistencyScore) || consistencyScore < 0.0 || consistencyScore > 1.0) {
      throw new IllegalArgumentException("consistencyScore must be finite and in [0,1]");
    }
  }

  /** Report used when fewer than three microphones or pair estimates are available. */
  public static TdoaConsistencyReport notEvaluated() {
    return new TdoaConsistencyReport(List.of(), 0, 0.0, 0.0, 0, 1.0);
  }

  /** Whether no physical violation exists and the consistency score remains usable. */
  public boolean reliable() {
    return physicalViolationCount == 0 && consistencyScore >= 0.5;
  }

  /** Confidence multiplier to apply to downstream pair-derived evidence. */
  public double confidenceMultiplier() {
    return physicalViolationCount == 0 ? consistencyScore : 0.0;
  }

  private static void requireNonNegativeFinite(double value, String name) {
    if (Double.isFinite(value) && value >= 0.0) {
      return;
    }
    throw new IllegalArgumentException(name + " must be finite and >= 0");
  }
}
