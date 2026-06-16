package org.hammer.audio.experimental.acoustic.simulation.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.hammer.audio.experimental.acoustic.feature.comparison.FeatureDifference;
import org.hammer.audio.experimental.acoustic.feature.comparison.SyntheticRealComparisonReport;
import org.hammer.audio.experimental.acoustic.simulation.WingbeatSignalParameters;
import org.hammer.audio.experimental.acoustic.wingbeat.WingbeatFeatureVector;
import org.junit.jupiter.api.Test;

class GeneratorComparisonReportTest {

  private static final double DELTA = 1e-9;

  private static WingbeatFeatureVector fv(double freqHz, double snr) {
    return new WingbeatFeatureVector(
        freqHz, List.of(), List.of(), freqHz, 0.0, 0.0, 0.0, 0.0, snr, 1.0, 0.9);
  }

  private static SyntheticRealComparisonReport buildReport(
      List<WingbeatFeatureVector> synthetic, List<WingbeatFeatureVector> real) {
    return new org.hammer.audio.experimental.acoustic.feature.comparison.SyntheticRealComparison()
        .compare(synthetic, real);
  }

  @Test
  void constructorThrowsOnNullBefore() {
    SyntheticRealComparisonReport report =
        buildReport(List.of(fv(500.0, 5.0)), List.of(fv(500.0, 5.0)));
    assertThrows(NullPointerException.class, () -> new GeneratorComparisonReport(null, report));
  }

  @Test
  void constructorThrowsOnNullAfter() {
    SyntheticRealComparisonReport report =
        buildReport(List.of(fv(500.0, 5.0)), List.of(fv(500.0, 5.0)));
    assertThrows(NullPointerException.class, () -> new GeneratorComparisonReport(report, null));
  }

  @Test
  void overallImprovementIsZeroWhenSameReport() {
    SyntheticRealComparisonReport report =
        buildReport(List.of(fv(500.0, 5.0)), List.of(fv(500.0, 5.0)));
    GeneratorComparisonReport comparison = new GeneratorComparisonReport(report, report);
    assertEquals(0.0, comparison.overallImprovement(), DELTA);
  }

  @Test
  void overallImprovementPositiveWhenAfterCloserToReal() {
    // Before: synthetic=400, real=600 → large difference
    SyntheticRealComparisonReport before =
        buildReport(List.of(fv(400.0, 5.0)), List.of(fv(600.0, 5.0)));
    // After: synthetic=580, real=600 → smaller difference
    SyntheticRealComparisonReport after =
        buildReport(List.of(fv(580.0, 5.0)), List.of(fv(600.0, 5.0)));

    GeneratorComparisonReport report = new GeneratorComparisonReport(before, after);

    double improvement = report.overallImprovement();
    assertNotNull(report);
    assertEquals(
        true, improvement > 0.0, "Improvement should be positive when after is closer to real");
  }

  @Test
  void perFeatureImprovementHasCorrectSize() {
    SyntheticRealComparisonReport before =
        buildReport(List.of(fv(400.0, 5.0)), List.of(fv(600.0, 5.0)));
    SyntheticRealComparisonReport after =
        buildReport(List.of(fv(580.0, 5.0)), List.of(fv(600.0, 5.0)));

    GeneratorComparisonReport report = new GeneratorComparisonReport(before, after);
    List<Double> improvements = report.perFeatureImprovement();

    assertEquals(before.differences().size(), improvements.size());
  }

  @Test
  void perFeatureImprovementIsUnmodifiable() {
    SyntheticRealComparisonReport report =
        buildReport(List.of(fv(500.0, 5.0)), List.of(fv(500.0, 5.0)));
    GeneratorComparisonReport comparison = new GeneratorComparisonReport(report, report);
    assertThrows(
        UnsupportedOperationException.class, () -> comparison.perFeatureImprovement().add(0.0));
  }

  @Test
  void ofFactoryCreatesFromCalibrationResult() {
    WingbeatSignalParameters baseline = WingbeatSignalParameters.of(400.0);
    List<WingbeatFeatureVector> real = List.of(fv(600.0, 5.0), fv(610.0, 6.0));
    GeneratorCalibrationService service = new GeneratorCalibrationService();
    CalibrationResult result = service.calibrate(baseline, real);

    GeneratorComparisonReport report = GeneratorComparisonReport.of(result);

    assertNotNull(report);
    assertEquals(result.beforeCalibration(), report.beforeCalibration());
    assertEquals(result.afterCalibration(), report.afterCalibration());
  }

  @Test
  void ofFactoryThrowsOnNull() {
    assertThrows(NullPointerException.class, () -> GeneratorComparisonReport.of(null));
  }

  @Test
  void meanRelativeDifferenceBeforeMatchesReport() {
    SyntheticRealComparisonReport before =
        buildReport(List.of(fv(400.0, 5.0)), List.of(fv(600.0, 5.0)));
    SyntheticRealComparisonReport after =
        buildReport(List.of(fv(500.0, 5.0)), List.of(fv(600.0, 5.0)));
    GeneratorComparisonReport report = new GeneratorComparisonReport(before, after);

    double expected =
        before.differences().stream()
            .mapToDouble(FeatureDifference::relativeDifference)
            .average()
            .orElse(0.0);
    assertEquals(expected, report.meanRelativeDifferenceBefore(), DELTA);
  }
}
