package org.hammer.audio.experimental.acoustic.simulation.calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.experimental.acoustic.feature.comparison.FeatureDifference;
import org.hammer.audio.experimental.acoustic.feature.comparison.SyntheticRealComparisonReport;

/**
 * Benchmark comparison between the original and calibrated generator.
 *
 * <p>Wraps the {@link SyntheticRealComparisonReport comparison reports} produced before and after
 * calibration and exposes improvement metrics:
 *
 * <ul>
 *   <li>{@link #meanRelativeDifferenceBefore()} / {@link #meanRelativeDifferenceAfter()} — overall
 *       deviation from real data for each generator
 *   <li>{@link #overallImprovement()} — relative reduction in mean deviation
 *   <li>{@link #perFeatureImprovement()} — per-feature absolute reduction in relative difference
 * </ul>
 *
 * @param beforeCalibration comparison report for the original (baseline) generator; must not be
 *     {@code null}
 * @param afterCalibration comparison report for the calibrated generator; must not be {@code null}
 */
public record GeneratorComparisonReport(
    SyntheticRealComparisonReport beforeCalibration,
    SyntheticRealComparisonReport afterCalibration) {

  // Validate fields.
  public GeneratorComparisonReport {
    Objects.requireNonNull(beforeCalibration, "beforeCalibration");
    Objects.requireNonNull(afterCalibration, "afterCalibration");
  }

  /**
   * Create a {@link GeneratorComparisonReport} from a {@link CalibrationResult}.
   *
   * @param result calibration result; must not be {@code null}
   * @return comparison report; never {@code null}
   */
  public static GeneratorComparisonReport of(CalibrationResult result) {
    Objects.requireNonNull(result, "result");
    return new GeneratorComparisonReport(result.beforeCalibration(), result.afterCalibration());
  }

  /**
   * Return the mean relative difference across all features before calibration.
   *
   * @return mean relative difference; {@code >= 0}
   */
  public double meanRelativeDifferenceBefore() {
    return beforeCalibration.differences().stream()
        .mapToDouble(FeatureDifference::relativeDifference)
        .average()
        .orElse(0.0);
  }

  /**
   * Return the mean relative difference across all features after calibration.
   *
   * @return mean relative difference; {@code >= 0}
   */
  public double meanRelativeDifferenceAfter() {
    return afterCalibration.differences().stream()
        .mapToDouble(FeatureDifference::relativeDifference)
        .average()
        .orElse(0.0);
  }

  /**
   * Return the overall improvement as {@code (before − after) / before}.
   *
   * <p>A positive value means the calibrated generator is closer to real data. Returns {@code 0}
   * when the before-calibration mean difference is zero.
   *
   * @return improvement fraction; positive = better
   */
  public double overallImprovement() {
    double before = meanRelativeDifferenceBefore();
    if (before == 0.0) {
      return 0.0;
    }
    return (before - meanRelativeDifferenceAfter()) / before;
  }

  /**
   * Return per-feature improvement as the absolute reduction in relative difference ({@code before
   * − after}) for each feature.
   *
   * <p>The list is ordered identically to {@link SyntheticRealComparisonReport#differences()}.
   * Positive values indicate features that improved; negative values indicate features that got
   * worse.
   *
   * @return per-feature improvement list; never {@code null}
   */
  public List<Double> perFeatureImprovement() {
    List<FeatureDifference> before = beforeCalibration.differences();
    List<FeatureDifference> after = afterCalibration.differences();
    int size = Math.min(before.size(), after.size());
    List<Double> improvements = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      improvements.add(before.get(i).relativeDifference() - after.get(i).relativeDifference());
    }
    return List.copyOf(improvements);
  }
}
