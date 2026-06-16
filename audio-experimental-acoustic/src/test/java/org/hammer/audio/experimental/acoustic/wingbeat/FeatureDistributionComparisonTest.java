package org.hammer.audio.experimental.acoustic.wingbeat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.hammer.audio.experimental.acoustic.dataset.DatasetAnalytics;
import org.junit.jupiter.api.Test;

class FeatureDistributionComparisonTest {

  // ── factory: identical distributions ─────────────────────────────────────

  @Test
  void identicalDistributionsYieldZeroDifferences() {
    DatasetAnalytics.DistributionStats stats =
        DatasetAnalytics.DistributionStats.of(List.of(300.0, 400.0, 500.0));

    FeatureDistributionComparison cmp = FeatureDistributionComparison.of("freq", stats, stats);

    assertEquals(0.0, cmp.absoluteDifference(), 1e-9);
    assertEquals(0.0, cmp.relativeDifference(), 1e-9);
  }

  // ── factory: shifted distributions ───────────────────────────────────────

  @Test
  void shiftedDistributionsYieldPositiveAbsoluteDifference() {
    DatasetAnalytics.DistributionStats synthetic =
        DatasetAnalytics.DistributionStats.of(List.of(300.0, 400.0, 500.0));
    DatasetAnalytics.DistributionStats real =
        DatasetAnalytics.DistributionStats.of(List.of(350.0, 450.0, 550.0));

    FeatureDistributionComparison cmp = FeatureDistributionComparison.of("freq", synthetic, real);

    assertTrue(cmp.absoluteDifference() > 0.0);
    assertEquals(Math.abs(real.mean() - synthetic.mean()), cmp.absoluteDifference(), 1e-9);
  }

  @Test
  void relativeDifferenceIsNormalisedToSyntheticMean() {
    DatasetAnalytics.DistributionStats synthetic =
        DatasetAnalytics.DistributionStats.of(List.of(400.0, 400.0, 400.0));
    DatasetAnalytics.DistributionStats real =
        DatasetAnalytics.DistributionStats.of(List.of(600.0, 600.0, 600.0));

    FeatureDistributionComparison cmp = FeatureDistributionComparison.of("freq", synthetic, real);

    // abs diff = 200, synthetic mean = 400 → relative = 0.5 (50 %)
    assertEquals(200.0, cmp.absoluteDifference(), 1e-9);
    assertEquals(0.5, cmp.relativeDifference(), 1e-9);
  }

  // ── factory: completely different distributions ───────────────────────────

  @Test
  void completelyDifferentDistributionsYieldLargeDifference() {
    DatasetAnalytics.DistributionStats synthetic =
        DatasetAnalytics.DistributionStats.of(List.of(300.0, 310.0, 320.0));
    DatasetAnalytics.DistributionStats real =
        DatasetAnalytics.DistributionStats.of(List.of(700.0, 710.0, 720.0));

    FeatureDistributionComparison cmp = FeatureDistributionComparison.of("freq", synthetic, real);

    assertTrue(cmp.absoluteDifference() > 300.0);
    assertTrue(cmp.relativeDifference() > 1.0);
  }

  // ── edge case: zero synthetic mean ────────────────────────────────────────

  @Test
  void zeroSyntheticMeanGivesZeroRelativeDifference() {
    DatasetAnalytics.DistributionStats synthetic =
        DatasetAnalytics.DistributionStats.of(List.of(0.0, 0.0));
    DatasetAnalytics.DistributionStats real =
        DatasetAnalytics.DistributionStats.of(List.of(500.0, 500.0));

    FeatureDistributionComparison cmp = FeatureDistributionComparison.of("freq", synthetic, real);

    assertEquals(500.0, cmp.absoluteDifference(), 1e-9);
    assertEquals(0.0, cmp.relativeDifference(), 1e-9);
  }

  // ── edge case: empty datasets ─────────────────────────────────────────────

  @Test
  void emptyDatasetsYieldZeroCounts() {
    DatasetAnalytics.DistributionStats empty = DatasetAnalytics.DistributionStats.of(List.of());

    FeatureDistributionComparison cmp = FeatureDistributionComparison.of("snr", empty, empty);

    assertEquals(0, cmp.syntheticStats().count());
    assertEquals(0, cmp.realStats().count());
    assertEquals(0.0, cmp.absoluteDifference(), 1e-9);
  }

  // ── validation ────────────────────────────────────────────────────────────

  @Test
  void blankFeatureNameIsRejected() {
    DatasetAnalytics.DistributionStats stats = DatasetAnalytics.DistributionStats.of(List.of());
    assertThrows(
        IllegalArgumentException.class, () -> FeatureDistributionComparison.of(" ", stats, stats));
  }

  @Test
  void nullSyntheticStatsIsRejected() {
    DatasetAnalytics.DistributionStats stats = DatasetAnalytics.DistributionStats.of(List.of());
    assertThrows(
        NullPointerException.class, () -> FeatureDistributionComparison.of("freq", null, stats));
  }

  @Test
  void nullRealStatsIsRejected() {
    DatasetAnalytics.DistributionStats stats = DatasetAnalytics.DistributionStats.of(List.of());
    assertThrows(
        NullPointerException.class, () -> FeatureDistributionComparison.of("freq", stats, null));
  }

  // ── Markdown export ───────────────────────────────────────────────────────

  @Test
  void toMarkdownContainsBothDatasetColumns() {
    DatasetAnalytics.DistributionStats synth =
        DatasetAnalytics.DistributionStats.of(List.of(300.0, 400.0));
    DatasetAnalytics.DistributionStats real =
        DatasetAnalytics.DistributionStats.of(List.of(500.0, 600.0));

    String md =
        FeatureDistributionComparison.of("Dominant Frequency (Hz)", synth, real).toMarkdown();

    assertTrue(md.contains("Dominant Frequency"));
    assertTrue(md.contains("Synthetic"));
    assertTrue(md.contains("Real"));
    assertNotNull(md);
  }

  @Test
  void toMarkdownContainsMeanRow() {
    DatasetAnalytics.DistributionStats synth =
        DatasetAnalytics.DistributionStats.of(List.of(400.0));
    DatasetAnalytics.DistributionStats real = DatasetAnalytics.DistributionStats.of(List.of(600.0));

    String md = FeatureDistributionComparison.of("SNR", synth, real).toMarkdown();

    assertTrue(md.contains("Mean"));
  }

  // ── compareDatasets integration ───────────────────────────────────────────

  @Test
  void compareDatasetsReturnsOneComparisonPerFeature() {
    // Empty lists produce zero-count stats — still returns 4 comparisons
    List<FeatureDistributionComparison> result =
        DatasetWingbeatEvaluationWorkflow.compareDatasets(List.of(), List.of());

    assertEquals(4, result.size());
  }

  @Test
  void toComparisonMarkdownContainsSyntheticVsRealHeading() {
    List<FeatureDistributionComparison> comparisons =
        DatasetWingbeatEvaluationWorkflow.compareDatasets(List.of(), List.of());

    String md = DatasetWingbeatEvaluationWorkflow.toComparisonMarkdown(comparisons);

    assertTrue(md.contains("Synthetic"));
    assertTrue(md.contains("Real"));
  }

  @Test
  void toComparisonMarkdownForEmptyListContainsNoFeaturesMessage() {
    String md = DatasetWingbeatEvaluationWorkflow.toComparisonMarkdown(List.of());

    assertTrue(md.contains("No features to compare"));
  }
}
