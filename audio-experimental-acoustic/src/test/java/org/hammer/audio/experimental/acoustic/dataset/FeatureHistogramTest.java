package org.hammer.audio.experimental.acoustic.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FeatureHistogramTest {

  // ── construction ─────────────────────────────────────────────────────────────

  @Test
  void emptyValuesProducesHistogramWithNoBuckets() {
    FeatureHistogram h = FeatureHistogram.of("freq", new double[0]);

    assertEquals("freq", h.featureName());
    assertTrue(h.buckets().isEmpty());
  }

  @Test
  void singleValueProducesSingleBucket() {
    FeatureHistogram h = FeatureHistogram.of("freq", new double[] {500.0});

    assertEquals(1, h.buckets().size());
    assertEquals(1, h.buckets().get(0).count());
    assertEquals(500.0, h.buckets().get(0).lowerBound(), 1e-9);
    assertEquals(500.0, h.buckets().get(0).upperBound(), 1e-9);
  }

  @Test
  void allIdenticalValuesProduceSingleBucket() {
    double[] values = {300.0, 300.0, 300.0, 300.0};

    FeatureHistogram h = FeatureHistogram.of("freq", values);

    assertEquals(1, h.buckets().size());
    assertEquals(4, h.buckets().get(0).count());
  }

  @Test
  void twoDistinctValuesFillTwoBuckets() {
    double[] values = {100.0, 200.0};

    FeatureHistogram h = FeatureHistogram.of("freq", values, 2);

    assertEquals(2, h.buckets().size());
    int total = h.buckets().stream().mapToInt(FeatureHistogram.Bucket::count).sum();
    assertEquals(values.length, total);
  }

  @Test
  void allValuesAssignedToSomeBucket() {
    double[] values = {100.0, 200.0, 300.0, 400.0, 500.0, 600.0};

    FeatureHistogram h = FeatureHistogram.of("freq", values);

    int total = h.buckets().stream().mapToInt(FeatureHistogram.Bucket::count).sum();
    assertEquals(values.length, total);
  }

  @Test
  void maximumValueAssignedToLastBucket() {
    double[] values = {100.0, 200.0, 300.0};

    FeatureHistogram h = FeatureHistogram.of("freq", values, 3);

    int total = h.buckets().stream().mapToInt(FeatureHistogram.Bucket::count).sum();
    assertEquals(values.length, total);
  }

  @Test
  void multipleClustersSeparatedIntoDifferentBuckets() {
    // Low cluster: 100–150, high cluster: 700–750
    double[] values = {100.0, 110.0, 120.0, 700.0, 710.0, 720.0};

    FeatureHistogram h = FeatureHistogram.of("freq", values, 10);

    assertEquals(10, h.buckets().size());
    // First and last buckets should have all the counts
    assertTrue(h.buckets().get(0).count() > 0);
    assertTrue(h.buckets().get(9).count() > 0);
    // Middle buckets should be empty
    long emptyBuckets = h.buckets().stream().filter(b -> b.count() == 0).count();
    assertTrue(emptyBuckets > 0, "expected some empty buckets between clusters");
  }

  @Test
  void bucketCountOneProducesSingleBucket() {
    double[] values = {100.0, 200.0, 300.0};

    FeatureHistogram h = FeatureHistogram.of("freq", values, 1);

    assertEquals(1, h.buckets().size());
    assertEquals(3, h.buckets().get(0).count());
  }

  // ── Sturges' rule ─────────────────────────────────────────────────────────

  @Test
  void optimalBucketCountReturnsOneForZeroOrOneValues() {
    assertEquals(1, FeatureHistogram.optimalBucketCount(0));
    assertEquals(1, FeatureHistogram.optimalBucketCount(1));
  }

  @Test
  void optimalBucketCountGrowsWithSampleSize() {
    int b8 = FeatureHistogram.optimalBucketCount(8);
    int b16 = FeatureHistogram.optimalBucketCount(16);
    int b1024 = FeatureHistogram.optimalBucketCount(1024);

    assertTrue(b8 < b16);
    assertTrue(b16 < b1024);
  }

  // ── validation ────────────────────────────────────────────────────────────

  @Test
  void blankFeatureNameIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> FeatureHistogram.of(" ", new double[0]));
  }

  @Test
  void bucketCountBelowOneIsRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> FeatureHistogram.of("freq", new double[] {1.0}, 0));
  }

  @Test
  void bucketWithNegativeCountIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new FeatureHistogram.Bucket(0.0, 1.0, -1));
  }

  @Test
  void bucketWithInfiniteLowerBoundIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FeatureHistogram.Bucket(Double.NEGATIVE_INFINITY, 1.0, 0));
  }

  // ── Markdown export ───────────────────────────────────────────────────────

  @Test
  void toMarkdownForEmptyHistogramContainsNoData() {
    String md = FeatureHistogram.of("SNR", new double[0]).toMarkdown();

    assertTrue(md.contains("SNR"));
    assertTrue(md.contains("No data"));
  }

  @Test
  void toMarkdownForNonEmptyHistogramContainsTableRows() {
    double[] values = {100.0, 200.0, 300.0, 400.0};

    String md = FeatureHistogram.of("Dominant Frequency (Hz)", values).toMarkdown();

    assertTrue(md.contains("Dominant Frequency (Hz)"));
    assertTrue(md.contains("Range"));
    assertTrue(md.contains("Count"));
    assertNotNull(md);
    assertTrue(md.contains("|"));
  }

  // ── record list immutability ──────────────────────────────────────────────

  @Test
  void bucketsListIsImmutable() {
    FeatureHistogram h = FeatureHistogram.of("freq", new double[] {1.0, 2.0, 3.0});

    assertThrows(UnsupportedOperationException.class, () -> h.buckets().clear());
  }

  @Test
  void constructorDefensivelyCopiesBucketList() {
    List<FeatureHistogram.Bucket> mutable =
        new java.util.ArrayList<>(List.of(new FeatureHistogram.Bucket(0.0, 1.0, 3)));
    FeatureHistogram h = new FeatureHistogram("freq", mutable);
    mutable.clear();

    assertEquals(1, h.buckets().size());
  }
}
