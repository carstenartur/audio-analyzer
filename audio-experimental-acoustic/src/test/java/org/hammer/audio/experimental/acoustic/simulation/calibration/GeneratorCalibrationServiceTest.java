package org.hammer.audio.experimental.acoustic.simulation.calibration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.hammer.audio.experimental.acoustic.simulation.WingbeatSignalParameters;
import org.hammer.audio.experimental.acoustic.wingbeat.WingbeatFeatureVector;
import org.junit.jupiter.api.Test;

class GeneratorCalibrationServiceTest {

  private final GeneratorCalibrationService service = new GeneratorCalibrationService();

  private static WingbeatFeatureVector fv(double freqHz, double snr, double jitter) {
    return new WingbeatFeatureVector(
        freqHz, List.of(), List.of(), freqHz, 0.0, 0.0, jitter, 0.0, snr, 1.0, 0.9);
  }

  @Test
  void calibrateThrowsOnNullBaseline() {
    List<WingbeatFeatureVector> real = List.of(fv(500.0, 5.0, 1.0));
    assertThrows(NullPointerException.class, () -> service.calibrate(null, real));
  }

  @Test
  void calibrateThrowsOnNullReal() {
    WingbeatSignalParameters baseline = WingbeatSignalParameters.of(500.0);
    assertThrows(NullPointerException.class, () -> service.calibrate(baseline, null));
  }

  @Test
  void calibrateThrowsOnEmptyReal() {
    WingbeatSignalParameters baseline = WingbeatSignalParameters.of(500.0);
    assertThrows(IllegalArgumentException.class, () -> service.calibrate(baseline, List.of()));
  }

  @Test
  void calibrateReturnsNonNullResult() {
    WingbeatSignalParameters baseline = WingbeatSignalParameters.mosquitoLike(550.0);
    List<WingbeatFeatureVector> real =
        List.of(fv(600.0, 8.0, 2.0), fv(610.0, 9.0, 2.5), fv(590.0, 7.5, 1.8));

    CalibrationResult result = service.calibrate(baseline, real);

    assertNotNull(result);
    assertNotNull(result.baselineParameters());
    assertNotNull(result.calibratedParameters());
    assertNotNull(result.beforeCalibration());
    assertNotNull(result.afterCalibration());
  }

  @Test
  void calibratedParametersFundamentalMatchesRealMean() {
    WingbeatSignalParameters baseline = WingbeatSignalParameters.of(400.0);
    List<WingbeatFeatureVector> real = List.of(fv(600.0, 5.0, 1.0), fv(600.0, 5.0, 1.0));

    CalibrationResult result = service.calibrate(baseline, real);

    // Calibrated params should reflect the real mean frequency (600 Hz)
    assertTrue(
        result.calibratedParameters().fundamentalFrequencyHz() > 500.0,
        "Calibrated frequency should be closer to real mean (600 Hz) than baseline (400 Hz)");
  }

  @Test
  void calibrationImprovementIsReasonableForLargeDivergence() {
    // Baseline far from real → expect positive improvement after calibration
    WingbeatSignalParameters baseline = WingbeatSignalParameters.of(200.0);
    List<WingbeatFeatureVector> real =
        List.of(
            fv(700.0, 10.0, 3.0), fv(710.0, 12.0, 2.5), fv(690.0, 9.0, 3.5), fv(705.0, 11.0, 3.0));

    CalibrationResult result = service.calibrate(baseline, real);

    // After calibration the deviation should be lower than before
    assertTrue(
        result.meanRelativeDifferenceAfter() <= result.meanRelativeDifferenceBefore(),
        "Calibrated generator should deviate less from real data than baseline");
  }

  @Test
  void calibrationIsReproducible() {
    WingbeatSignalParameters baseline = WingbeatSignalParameters.mosquitoLike(450.0);
    List<WingbeatFeatureVector> real = List.of(fv(500.0, 8.0, 2.0), fv(510.0, 9.0, 2.5));

    CalibrationResult r1 = service.calibrate(baseline, real);
    CalibrationResult r2 = service.calibrate(baseline, real);

    // Deterministic: same inputs → same improvement value
    assertTrue(
        Math.abs(r1.improvement() - r2.improvement()) < 1e-9, "Calibration must be deterministic");
  }

  @Test
  void calibrationWithSingleVector() {
    WingbeatSignalParameters baseline = WingbeatSignalParameters.of(500.0);
    List<WingbeatFeatureVector> real = List.of(fv(600.0, 7.0, 2.0));

    CalibrationResult result = service.calibrate(baseline, real);

    assertNotNull(result);
    assertNotNull(result.calibratedParameters());
  }
}
