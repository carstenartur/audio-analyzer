package org.hammer.audio.experimental.acoustic.dataset;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Histogram of a single numeric feature computed from a collection of recordings.
 *
 * <p>Bucket boundaries are determined automatically from the value range using Sturges' rule when
 * no explicit bucket count is supplied. All bucket counts are non-negative integers and the buckets
 * partition the observed range without overlap.
 *
 * <p>An empty histogram (no values) has an empty bucket list and is a valid representation of a
 * feature for which no data is available. Use {@link #toMarkdown()} to render a human-readable
 * table for inclusion in evaluation reports.
 *
 * @param featureName human-readable name of the feature; must not be blank
 * @param buckets ordered list of histogram buckets; may be empty when the input is empty
 */
@SuppressWarnings("PMD.DanglingJavadoc")
public record FeatureHistogram(String featureName, List<Bucket> buckets) {

  /** Validate and defensively copy fields. */
  public FeatureHistogram {
    Objects.requireNonNull(featureName, "featureName");
    Objects.requireNonNull(buckets, "buckets");
    if (featureName.isBlank()) {
      throw new IllegalArgumentException("featureName must not be blank");
    }
    buckets = List.copyOf(buckets);
  }

  /**
   * Build a histogram from an array of values using an automatically determined bucket count.
   *
   * <p>The bucket count is chosen with Sturges' rule: {@code ceil(log2(n)) + 1}, clamped to at
   * least {@code 1}. When all values are identical, a single bucket is returned.
   *
   * @param featureName human-readable feature name; must not be blank
   * @param values array of observed values; must not be {@code null}; may be empty
   * @return histogram; never {@code null}
   */
  public static FeatureHistogram of(String featureName, double[] values) {
    Objects.requireNonNull(values, "values");
    return of(featureName, values, optimalBucketCount(values.length));
  }

  /**
   * Build a histogram from an array of values with a specified bucket count.
   *
   * @param featureName human-readable feature name; must not be blank
   * @param values array of observed values; must not be {@code null}; may be empty
   * @param bucketCount desired number of buckets; must be {@code >= 1}
   * @return histogram; never {@code null}
   */
  public static FeatureHistogram of(String featureName, double[] values, int bucketCount) {
    Objects.requireNonNull(featureName, "featureName");
    Objects.requireNonNull(values, "values");
    if (bucketCount < 1) {
      throw new IllegalArgumentException("bucketCount must be >= 1");
    }
    if (values.length == 0) {
      return new FeatureHistogram(featureName, List.of());
    }
    double min = values[0];
    double max = values[0];
    for (double v : values) {
      if (v < min) {
        min = v;
      }
      if (v > max) {
        max = v;
      }
    }
    if (Double.doubleToLongBits(min) == Double.doubleToLongBits(max)) {
      return new FeatureHistogram(featureName, List.of(new Bucket(min, max, values.length)));
    }
    double range = max - min;
    double bucketWidth = range / bucketCount;
    int[] counts = new int[bucketCount];
    for (double v : values) {
      int idx = (int) ((v - min) / bucketWidth);
      if (idx >= bucketCount) {
        idx = bucketCount - 1;
      }
      counts[idx]++;
    }
    List<Bucket> buckets = new ArrayList<>(bucketCount);
    for (int i = 0; i < bucketCount; i++) {
      double lower = min + i * bucketWidth;
      double upper = min + (i + 1) * bucketWidth;
      buckets.add(new Bucket(lower, upper, counts[i]));
    }
    return new FeatureHistogram(featureName, buckets);
  }

  /**
   * Render this histogram as a Markdown table section.
   *
   * @return Markdown snippet; never {@code null}
   */
  @SuppressWarnings("PMD.ConsecutiveLiteralAppends")
  public String toMarkdown() {
    if (buckets.isEmpty()) {
      return "### " + featureName + "\n\n- No data\n\n";
    }
    StringBuilder sb = new StringBuilder(256);
    sb.append("### ").append(featureName).append("\n\n").append("| Range | Count |\n|---|---:|\n");
    for (Bucket b : buckets) {
      sb.append("| [")
          .append(String.format(Locale.ROOT, "%.3f", b.lowerBound()))
          .append(", ")
          .append(String.format(Locale.ROOT, "%.3f", b.upperBound()))
          .append(") | ")
          .append(b.count())
          .append(" |\n");
    }
    sb.append('\n');
    return sb.toString();
  }

  /**
   * Compute the optimal bucket count for {@code n} observations using Sturges' rule.
   *
   * @param n number of observations
   * @return number of buckets, always {@code >= 1}
   */
  static int optimalBucketCount(int n) {
    if (n <= 1) {
      return 1;
    }
    return Math.max(1, (int) Math.ceil(Math.log(n) / Math.log(2.0)) + 1);
  }

  /**
   * One bucket of a {@link FeatureHistogram}.
   *
   * @param lowerBound inclusive lower boundary of the bucket
   * @param upperBound exclusive upper boundary of the bucket (except for the last bucket, which is
   *     inclusive)
   * @param count number of observations falling into this bucket; must be {@code >= 0}
   */
  @SuppressWarnings("PMD.DanglingJavadoc")
  public record Bucket(double lowerBound, double upperBound, int count) {

    /** Validate bucket fields. */
    public Bucket {
      if (!Double.isFinite(lowerBound)) {
        throw new IllegalArgumentException("lowerBound must be finite");
      }
      if (!Double.isFinite(upperBound)) {
        throw new IllegalArgumentException("upperBound must be finite");
      }
      if (count < 0) {
        throw new IllegalArgumentException("count must be >= 0");
      }
    }
  }
}
