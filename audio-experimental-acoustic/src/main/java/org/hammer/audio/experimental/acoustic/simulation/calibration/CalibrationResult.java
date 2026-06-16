package org.hammer.audio.experimental.acoustic.simulation.calibration;

import java.util.Objects;
import org.hammer.audio.experimental.acoustic.feature.comparison.FeatureDifference;
import org.hammer.audio.experimental.acoustic.feature.comparison.SyntheticRealComparisonReport;
import org.hammer.audio.experimental.acoustic.simulation.WingbeatSignalParameters;

/**
 * Immutable result of a generator calibration run.
 *
 * <p>Produced by {@link GeneratorCalibrationService}. Contains the baseline and calibrated
 * parameters, the {@link SyntheticRealComparisonReport} before calibration (measuring how far the
 * baseline generator deviates from real data) and the equivalent report after calibration.
 *
 * @param baselineParameters the original generator parameters before calibration; must not be
 *     {@code null}
 * @param calibratedParameters the estimated parameters derived from real recordings; must not be
 *     {@code null}
 * @param beforeCalibration comparison report for the baseline generator; must not be {@code null}
 * @param afterCalibration comparison report for the calibrated generator; must not be {@code null}
 */
public record CalibrationResult(
    WingbeatSignalParameters baselineParameters,
    WingbeatSignalParameters calibratedParameters,
    SyntheticRealComparisonReport beforeCalibration,
    SyntheticRealComparisonReport afterCalibration) {

  // Validate all fields.
  public CalibrationResult {
    Objects.requireNonNull(baselineParameters, "baselineParameters");
    Objects.requireNonNull(calibratedParameters, "calibratedParameters");
    Objects.requireNonNull(beforeCalibration, "beforeCalibration");
    Objects.requireNonNull(afterCalibration, "afterCalibration");
  }

  /**
   * Return the mean relative difference across all features before calibration.
   *
   * @return mean relative difference in {@code [0, ∞)}
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
   * @return mean relative difference in {@code [0, ∞)}
   */
  public double meanRelativeDifferenceAfter() {
    return afterCalibration.differences().stream()
        .mapToDouble(FeatureDifference::relativeDifference)
        .average()
        .orElse(0.0);
  }

  /**
   * Return the overall improvement as a fraction: {@code (before − after) / before}.
   *
   * <p>A positive value indicates that the calibrated generator is closer to real data. Returns
   * {@code 0} when the before-calibration mean difference is zero.
   *
   * @return improvement fraction; positive means better, negative means worse
   */
  public double improvement() {
    double before = meanRelativeDifferenceBefore();
    if (before == 0.0) {
      return 0.0;
    }
    return (before - meanRelativeDifferenceAfter()) / before;
  }
}
