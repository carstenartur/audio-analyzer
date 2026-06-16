/**
 * Generator calibration services for improving synthetic wingbeat signal parameters using real
 * recordings.
 *
 * <p>Workflow:
 *
 * <pre>
 *   Real Dataset
 *       ↓
 *   Feature Statistics
 *       ↓
 *   Parameter Estimation ({@link
 *       org.hammer.audio.experimental.acoustic.simulation.calibration.SyntheticParameterEstimator})
 *       ↓
 *   Synthetic Generator Configuration ({@link
 *       org.hammer.audio.experimental.acoustic.simulation.calibration.CalibrationResult})
 * </pre>
 *
 * <p>The {@link
 * org.hammer.audio.experimental.acoustic.simulation.calibration.GeneratorCalibrationService}
 * orchestrates the full pipeline. Use {@link
 * org.hammer.audio.experimental.acoustic.simulation.calibration.SyntheticCalibrationAnalysis} to
 * compare a generator configuration against real data. Use {@link
 * org.hammer.audio.experimental.acoustic.simulation.calibration.GeneratorComparisonReport} to
 * benchmark the original generator against the calibrated one.
 */
package org.hammer.audio.experimental.acoustic.simulation.calibration;
