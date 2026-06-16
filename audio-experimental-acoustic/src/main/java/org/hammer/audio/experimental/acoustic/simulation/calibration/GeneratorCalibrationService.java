package org.hammer.audio.experimental.acoustic.simulation.calibration;

import java.util.List;
import java.util.Objects;
import org.hammer.audio.experimental.acoustic.feature.comparison.SyntheticRealComparisonReport;
import org.hammer.audio.experimental.acoustic.simulation.WingbeatSignalParameters;
import org.hammer.audio.experimental.acoustic.wingbeat.WingbeatFeatureVector;

/**
 * Orchestrates the full generator calibration workflow.
 *
 * <p>Workflow:
 *
 * <pre>
 *   Real Dataset
 *       ↓
 *   Feature Statistics
 *       ↓
 *   Parameter Estimation ({@link SyntheticParameterEstimator})
 *       ↓
 *   Synthetic Generator Configuration ({@link CalibrationResult})
 * </pre>
 *
 * <p>The service:
 *
 * <ol>
 *   <li>Analyses the baseline generator against the real corpus to obtain the before-calibration
 *       deviation report.
 *   <li>Estimates improved parameters from the real corpus using {@link
 *       SyntheticParameterEstimator}.
 *   <li>Analyses the calibrated generator against the real corpus for the after-calibration
 *       deviation report.
 *   <li>Returns a {@link CalibrationResult} containing both reports and the calibrated parameters.
 * </ol>
 *
 * <p>The calibrated {@link WingbeatSignalParameters} can be saved and reused directly with {@link
 * org.hammer.audio.experimental.acoustic.simulation.WingbeatSignalGenerator}.
 *
 * <p>This service is stateless and may be called concurrently.
 */
public final class GeneratorCalibrationService {

  private final SyntheticCalibrationAnalysis analysis;
  private final SyntheticParameterEstimator estimator;

  /** Create a calibration service with default analysis and estimator instances. */
  public GeneratorCalibrationService() {
    this.analysis = new SyntheticCalibrationAnalysis();
    this.estimator = new SyntheticParameterEstimator();
  }

  /**
   * Run the full calibration pipeline using the given baseline parameters and real corpus.
   *
   * @param baseline baseline generator parameters to compare against; must not be {@code null}
   * @param real real recording feature vectors; must not be {@code null} or empty
   * @return calibration result including before/after reports and calibrated parameters; never
   *     {@code null}
   */
  public CalibrationResult calibrate(
      WingbeatSignalParameters baseline, List<WingbeatFeatureVector> real) {
    Objects.requireNonNull(baseline, "baseline");
    Objects.requireNonNull(real, "real");
    if (real.isEmpty()) {
      throw new IllegalArgumentException("real corpus must not be empty");
    }

    SyntheticRealComparisonReport before = analysis.analyse(baseline, real);
    WingbeatSignalParameters calibrated = estimator.estimate(real);
    SyntheticRealComparisonReport after = analysis.analyse(calibrated, real);

    return new CalibrationResult(baseline, calibrated, before, after);
  }
}
