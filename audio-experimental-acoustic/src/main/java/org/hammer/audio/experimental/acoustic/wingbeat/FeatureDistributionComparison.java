package org.hammer.audio.experimental.acoustic.wingbeat;

import java.util.Locale;
import java.util.Objects;
import org.hammer.audio.experimental.acoustic.dataset.DatasetAnalytics;

/**
 * Comparison of the distribution of a single feature between a synthetic dataset and a real
 * dataset.
 *
 * <p>A {@code FeatureDistributionComparison} captures descriptive statistics for the same feature
 * extracted from two corpora — synthetic (simulated) recordings and real field recordings — along
 * with computed absolute and relative differences between the two means.
 *
 * <p>Use {@link #of(String, DatasetAnalytics.DistributionStats, DatasetAnalytics.DistributionStats)
 * } to construct an instance from pre-computed statistics and {@link #toMarkdown()} to render a
 * human-readable comparison table suitable for evaluation reports.
 *
 * @param featureName human-readable feature name; must not be blank
 * @param syntheticStats descriptive statistics over the synthetic dataset; must not be {@code null}
 * @param realStats descriptive statistics over the real dataset; must not be {@code null}
 * @param absoluteDifference absolute difference between the two means ({@code |real − synthetic|});
 *     must be finite
 * @param relativeDifference relative difference as a fraction ({@code absoluteDifference /
 *     |syntheticMean|}, or {@code 0} when the synthetic mean is zero); must be finite
 */
@SuppressWarnings("PMD.DanglingJavadoc")
public record FeatureDistributionComparison(
    String featureName,
    DatasetAnalytics.DistributionStats syntheticStats,
    DatasetAnalytics.DistributionStats realStats,
    double absoluteDifference,
    double relativeDifference) {

  /** Validate fields. */
  public FeatureDistributionComparison {
    Objects.requireNonNull(featureName, "featureName");
    Objects.requireNonNull(syntheticStats, "syntheticStats");
    Objects.requireNonNull(realStats, "realStats");
    if (featureName.isBlank()) {
      throw new IllegalArgumentException("featureName must not be blank");
    }
    if (!Double.isFinite(absoluteDifference)) {
      throw new IllegalArgumentException("absoluteDifference must be finite");
    }
    if (!Double.isFinite(relativeDifference)) {
      throw new IllegalArgumentException("relativeDifference must be finite");
    }
  }

  /**
   * Build a comparison from pre-computed statistics for the same feature in two corpora.
   *
   * <p>The absolute difference is {@code |realMean − syntheticMean|}. The relative difference is
   * {@code absoluteDifference / |syntheticMean|} when {@code syntheticMean != 0}, or {@code 0}
   * otherwise.
   *
   * @param featureName feature name; must not be blank
   * @param synthetic statistics over the synthetic corpus; must not be {@code null}
   * @param real statistics over the real corpus; must not be {@code null}
   * @return comparison record; never {@code null}
   */
  public static FeatureDistributionComparison of(
      String featureName,
      DatasetAnalytics.DistributionStats synthetic,
      DatasetAnalytics.DistributionStats real) {
    Objects.requireNonNull(featureName, "featureName");
    Objects.requireNonNull(synthetic, "synthetic");
    Objects.requireNonNull(real, "real");
    double absDiff = Math.abs(real.mean() - synthetic.mean());
    double relDiff = synthetic.mean() == 0.0 ? 0.0 : absDiff / Math.abs(synthetic.mean());
    return new FeatureDistributionComparison(featureName, synthetic, real, absDiff, relDiff);
  }

  /**
   * Render this comparison as a Markdown table section.
   *
   * @return Markdown snippet; never {@code null}
   */
  @SuppressWarnings({
    "PMD.ConsecutiveLiteralAppends",
    "PMD.ConsecutiveAppendsShouldReuse",
    "PMD.AvoidDuplicateLiterals"
  })
  public String toMarkdown() {
    StringBuilder sb = new StringBuilder(256);
    sb.append("### ").append(featureName).append("\n\n");
    sb.append("| Statistic | Synthetic | Real | Δ abs | Δ rel |\n|---|---:|---:|---:|---:|\n");
    sb.append("| Count | ")
        .append(syntheticStats.count())
        .append(" | ")
        .append(realStats.count())
        .append(" | | |\n");
    sb.append("| Mean | ")
        .append(String.format(Locale.ROOT, "%.3f", syntheticStats.mean()))
        .append(" | ")
        .append(String.format(Locale.ROOT, "%.3f", realStats.mean()))
        .append(" | ")
        .append(String.format(Locale.ROOT, "%.3f", absoluteDifference))
        .append(" | ")
        .append(String.format(Locale.ROOT, "%.1f%%", relativeDifference * 100.0))
        .append(" |\n");
    sb.append("| Std dev | ")
        .append(String.format(Locale.ROOT, "%.3f", syntheticStats.stddev()))
        .append(" | ")
        .append(String.format(Locale.ROOT, "%.3f", realStats.stddev()))
        .append(" | | |\n");
    sb.append("| Min | ")
        .append(String.format(Locale.ROOT, "%.3f", syntheticStats.min()))
        .append(" | ")
        .append(String.format(Locale.ROOT, "%.3f", realStats.min()))
        .append(" | | |\n");
    sb.append("| Max | ")
        .append(String.format(Locale.ROOT, "%.3f", syntheticStats.max()))
        .append(" | ")
        .append(String.format(Locale.ROOT, "%.3f", realStats.max()))
        .append(" | | |\n");
    sb.append('\n');
    return sb.toString();
  }
}
