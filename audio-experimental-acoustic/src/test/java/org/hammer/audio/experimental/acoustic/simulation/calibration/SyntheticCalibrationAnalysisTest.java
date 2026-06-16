package org.hammer.audio.experimental.acoustic.simulation.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.hammer.audio.experimental.acoustic.feature.comparison.SyntheticRealComparisonReport;
import org.hammer.audio.experimental.acoustic.simulation.WingbeatSignalParameters;
import org.hammer.audio.experimental.acoustic.wingbeat.WingbeatFeatureVector;
import org.junit.jupiter.api.Test;

class SyntheticCalibrationAnalysisTest {

  private static final double DELTA = 1e-9;

  private final SyntheticCalibrationAnalysis analysis = new SyntheticCalibrationAnalysis();

  private static WingbeatFeatureVector fv(double freqHz, double snr, double jitter) {
    return new WingbeatFeatureVector(
        freqHz, List.of(), List.of(), freqHz, 0.0, 0.0, jitter, 0.0, snr, 1.0, 0.9);
  }

  @Test
  void analyseReturnsReportForValidInputs() {
    WingbeatSignalParameters params = WingbeatSignalParameters.of(500.0);
    List<WingbeatFeatureVector> real = List.of(fv(500.0, 5.0, 1.0), fv(510.0, 6.0, 1.5));

    SyntheticRealComparisonReport report = analysis.analyse(params, real);

    assertNotNull(report);
    assertFalse(report.differences().isEmpty());
  }

  @Test
  void analyseThrowsOnNullParams() {
    List<WingbeatFeatureVector> real = List.of(fv(500.0, 5.0, 1.0));
    assertThrows(NullPointerException.class, () -> analysis.analyse(null, real));
  }

  @Test
  void analyseThrowsOnNullReal() {
    WingbeatSignalParameters params = WingbeatSignalParameters.of(500.0);
    assertThrows(NullPointerException.class, () -> analysis.analyse(params, null));
  }

  @Test
  void analyseThrowsOnEmptyReal() {
    WingbeatSignalParameters params = WingbeatSignalParameters.of(500.0);
    assertThrows(IllegalArgumentException.class, () -> analysis.analyse(params, List.of()));
  }

  @Test
  void generateSyntheticVectorsProducesCorrectCount() {
    WingbeatSignalParameters params = WingbeatSignalParameters.of(500.0);
    List<WingbeatFeatureVector> vectors =
        SyntheticCalibrationAnalysis.generateSyntheticVectors(params, 50, 42L);
    assertEquals(50, vectors.size());
  }

  @Test
  void generateSyntheticVectorsAreUnmodifiable() {
    WingbeatSignalParameters params = WingbeatSignalParameters.of(500.0);
    List<WingbeatFeatureVector> vectors =
        SyntheticCalibrationAnalysis.generateSyntheticVectors(params, 5, 42L);
    assertThrows(UnsupportedOperationException.class, () -> vectors.add(fv(1.0, 1.0, 0.0)));
  }

  @Test
  void generateSyntheticVectorsHaveFundamentalNearParams() {
    WingbeatSignalParameters params = WingbeatSignalParameters.of(500.0);
    List<WingbeatFeatureVector> vectors =
        SyntheticCalibrationAnalysis.generateSyntheticVectors(params, 200, 42L);
    double meanFreq =
        vectors.stream()
            .mapToDouble(WingbeatFeatureVector::fundamentalFrequencyHz)
            .average()
            .orElseThrow();
    // No jitter ⇒ all freqs should be exactly 500 Hz
    assertEquals(500.0, meanFreq, DELTA);
  }

  @Test
  void generateSyntheticVectorsJitterSpreadWithinBounds() {
    WingbeatSignalParameters params =
        new WingbeatSignalParameters(500.0, 1, null, 0.0, 0.0, 0.0, 10.0, 0.0);
    List<WingbeatFeatureVector> vectors =
        SyntheticCalibrationAnalysis.generateSyntheticVectors(params, 1000, 0L);
    double min =
        vectors.stream()
            .mapToDouble(WingbeatFeatureVector::fundamentalFrequencyHz)
            .min()
            .orElseThrow();
    double max =
        vectors.stream()
            .mapToDouble(WingbeatFeatureVector::fundamentalFrequencyHz)
            .max()
            .orElseThrow();
    assertTrue(min >= 490.0, "min should be >= fundamental - jitter");
    assertTrue(max <= 510.0, "max should be <= fundamental + jitter");
  }

  @Test
  void estimateSnrIsZeroWhenNoNoiseAmplitude() {
    assertEquals(0.0, SyntheticCalibrationAnalysis.estimateSnr(List.of(1.0, 0.5), 0.0), DELTA);
  }

  @Test
  void estimateSnrEqualsRatioOfTotalAmplitudeToNoise() {
    // total amplitude = 1.0 + 0.5 = 1.5, noise = 0.5 → SNR = 3.0
    double snr = SyntheticCalibrationAnalysis.estimateSnr(List.of(1.0, 0.5), 0.5);
    assertEquals(3.0, snr, DELTA);
  }

  @Test
  void generateSyntheticVectorsThrowsOnZeroCount() {
    WingbeatSignalParameters params = WingbeatSignalParameters.of(500.0);
    assertThrows(
        IllegalArgumentException.class,
        () -> SyntheticCalibrationAnalysis.generateSyntheticVectors(params, 0, 0L));
  }

  @Test
  void generateSyntheticVectorsReproducible() {
    WingbeatSignalParameters params =
        new WingbeatSignalParameters(500.0, 1, null, 0.0, 0.0, 0.0, 5.0, 0.0);
    List<WingbeatFeatureVector> run1 =
        SyntheticCalibrationAnalysis.generateSyntheticVectors(params, 10, 77L);
    List<WingbeatFeatureVector> run2 =
        SyntheticCalibrationAnalysis.generateSyntheticVectors(params, 10, 77L);
    for (int i = 0; i < run1.size(); i++) {
      assertEquals(
          run1.get(i).fundamentalFrequencyHz(),
          run2.get(i).fundamentalFrequencyHz(),
          DELTA,
          "Run must be deterministic for the same seed");
    }
  }
}
