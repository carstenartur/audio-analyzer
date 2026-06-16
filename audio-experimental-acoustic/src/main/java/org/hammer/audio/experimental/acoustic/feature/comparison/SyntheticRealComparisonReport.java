package org.hammer.audio.experimental.acoustic.feature.comparison;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Comparison report between synthetic and real wingbeat feature distributions.
 *
 * <p>Contains one {@link FeatureDifference} per feature and exposes a {@link
 * #generatorWeaknesses()} accessor that returns the subset of features where the relative mean
 * difference exceeds a configurable threshold.
 *
 * @param differences per-feature differences in extraction order; must not be empty
 * @param weaknessThreshold relative-difference threshold above which a feature is considered a
 *     generator weakness; must be finite and {@code > 0}
 */
public record SyntheticRealComparisonReport(
    List<FeatureDifference> differences, double weaknessThreshold) {

  /** Default relative-difference threshold used by {@link SyntheticRealComparison}. */
  public static final double DEFAULT_WEAKNESS_THRESHOLD = 0.20;

  /** Validate and defensively copy fields. */
  public SyntheticRealComparisonReport {
    Objects.requireNonNull(differences, "differences");
    if (differences.isEmpty()) {
      throw new IllegalArgumentException("differences must not be empty");
    }
    if (!Double.isFinite(weaknessThreshold) || weaknessThreshold <= 0.0) {
      throw new IllegalArgumentException("weaknessThreshold must be finite and > 0");
    }
    differences = List.copyOf(differences);
  }

  /**
   * Return features where {@link FeatureDifference#relativeDifference()} exceeds the configured
   * {@link #weaknessThreshold()}.
   *
   * @return list of generator weaknesses; never {@code null}, may be empty
   */
  public List<FeatureDifference> generatorWeaknesses() {
    List<FeatureDifference> weak = new ArrayList<>();
    for (FeatureDifference diff : differences) {
      if (diff.relativeDifference() > weaknessThreshold) {
        weak.add(diff);
      }
    }
    return List.copyOf(weak);
  }
}
