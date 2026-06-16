package org.hammer.audio.experimental.acoustic.feature.ranking;

import org.hammer.audio.experimental.acoustic.feature.evaluation.FeatureEvaluationEntry;

/**
 * Scores features using the Fisher discriminant ratio.
 *
 * <p>The Fisher ratio is defined as {@code betweenClassVariance / withinClassVariance}. A higher
 * ratio means that class means are further apart relative to within-class spread, indicating better
 * linear separability. When the within-class variance is zero the score is zero (avoid division by
 * zero for constant features).
 */
public final class FisherScorer implements FeatureScorer {

  @Override
  public double score(FeatureEvaluationEntry entry) {
    return entry.separation().fisherRatio();
  }

  @Override
  public String name() {
    return "fisherRatio";
  }
}
