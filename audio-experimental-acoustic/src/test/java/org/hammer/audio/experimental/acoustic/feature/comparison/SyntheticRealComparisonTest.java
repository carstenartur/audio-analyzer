package org.hammer.audio.experimental.acoustic.feature.comparison;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.hammer.audio.experimental.acoustic.wingbeat.WingbeatFeatureVector;
import org.junit.jupiter.api.Test;

class SyntheticRealComparisonTest {

  private static final double DELTA = 1e-9;

  private final SyntheticRealComparison comparison = new SyntheticRealComparison();

  private static WingbeatFeatureVector fv(double freqHz, double snr) {
    return new WingbeatFeatureVector(
        freqHz, List.of(), List.of(), freqHz, 0.0, 0.0, 0.0, 0.0, snr, 1.0, 0.9);
  }

  @Test
  void reportContainsDifferenceForFundamentalFrequency() {
    List<WingbeatFeatureVector> synthetic = List.of(fv(500.0, 5.0), fv(500.0, 5.0));
    List<WingbeatFeatureVector> real = List.of(fv(600.0, 5.0), fv(600.0, 5.0));

    SyntheticRealComparisonReport report = comparison.compare(synthetic, real);

    FeatureDifference diff =
        report.differences().stream()
            .filter(d -> "fundamentalFrequencyHz".equals(d.featureName()))
            .findFirst()
            .orElseThrow();

    assertEquals(500.0, diff.syntheticMean(), DELTA);
    assertEquals(600.0, diff.realMean(), DELTA);
    assertEquals(100.0, diff.absoluteDifference(), DELTA);
    assertEquals(0.2, diff.relativeDifference(), DELTA);
  }

  @Test
  void generatorWeaknessesDetectedWhenRelativeDiffExceedsThreshold() {
    // Synthetic SNR = 5, real SNR = 10 → relative diff = 1.0 (100%) > threshold 0.20
    List<WingbeatFeatureVector> synthetic = List.of(fv(500.0, 5.0));
    List<WingbeatFeatureVector> real = List.of(fv(500.0, 10.0));

    SyntheticRealComparisonReport report = comparison.compare(synthetic, real);

    List<FeatureDifference> weaknesses = report.generatorWeaknesses();
    assertFalse(weaknesses.isEmpty(), "SNR difference should be flagged as a generator weakness");
    assertTrue(weaknesses.stream().anyMatch(d -> "signalToNoiseRatio".equals(d.featureName())));
  }

  @Test
  void noWeaknessesWhenDistributionsMatch() {
    List<WingbeatFeatureVector> synthetic = List.of(fv(500.0, 5.0), fv(500.0, 5.0));
    List<WingbeatFeatureVector> real = List.of(fv(500.0, 5.0), fv(500.0, 5.0));

    SyntheticRealComparisonReport report = comparison.compare(synthetic, real);

    assertTrue(
        report.generatorWeaknesses().isEmpty(),
        "Identical distributions should produce no weaknesses");
  }

  @Test
  void zScoreIsNormalisedByStdDev() {
    // Synthetic freqs: [400, 600] → mean=500, stdDev=100
    // Real freqs: [400, 600] → mean=500 → zScore = 0
    List<WingbeatFeatureVector> synthetic = List.of(fv(400.0, 5.0), fv(600.0, 5.0));
    List<WingbeatFeatureVector> real = List.of(fv(400.0, 5.0), fv(600.0, 5.0));

    SyntheticRealComparisonReport report = comparison.compare(synthetic, real);

    FeatureDifference diff =
        report.differences().stream()
            .filter(d -> "fundamentalFrequencyHz".equals(d.featureName()))
            .findFirst()
            .orElseThrow();
    assertEquals(0.0, diff.zScore(), DELTA);
  }

  @Test
  void customThresholdIsRespected() {
    // With threshold = 0.05, 10% difference should be flagged
    List<WingbeatFeatureVector> synthetic = List.of(fv(500.0, 5.0));
    List<WingbeatFeatureVector> real = List.of(fv(550.0, 5.0)); // 10% freq difference

    SyntheticRealComparisonReport report = comparison.compare(synthetic, real, 0.05);

    assertTrue(
        report.generatorWeaknesses().stream()
            .anyMatch(d -> "fundamentalFrequencyHz".equals(d.featureName())));
  }
}
