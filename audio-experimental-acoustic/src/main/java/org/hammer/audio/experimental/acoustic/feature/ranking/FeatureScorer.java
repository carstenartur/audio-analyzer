package org.hammer.audio.experimental.acoustic.feature.ranking;

import org.hammer.audio.experimental.acoustic.feature.evaluation.FeatureEvaluationEntry;

/**
 * Contract for feature-scoring strategies that estimate how useful a feature is for classification.
 *
 * <p>Implementations must be stateless and deterministic so that rankings are reproducible.
 * Register implementations with {@link FeatureRankingService} to include them in the ranking.
 */
@FunctionalInterface
public interface FeatureScorer {

  /**
   * Compute a non-negative discriminability score for the given feature evaluation entry.
   *
   * <p>Higher scores indicate that the feature is estimated to be more useful for classification.
   * Scores from different scorers are not necessarily on the same scale; they are used for
   * within-scorer comparisons only.
   *
   * @param entry the feature evaluation entry; must not be {@code null}
   * @return non-negative score; must be finite and {@code >= 0}
   */
  double score(FeatureEvaluationEntry entry);

  /**
   * Human-readable scorer name used as a map key in {@link FeatureRankingEntry#scores()}.
   *
   * <p>Defaults to the simple class name; override when the default would produce an ambiguous key.
   *
   * @return scorer name; never blank
   */
  default String name() {
    return getClass().getSimpleName();
  }
}
