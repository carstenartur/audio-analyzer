package org.hammer.audio.experimental.acoustic.feature.ranking;

import org.hammer.audio.experimental.acoustic.feature.evaluation.FeatureEvaluationEntry;

/**
 * Scores features by the weighted between-class variance of the feature values.
 *
 * <p>A higher between-class variance indicates that the class means are far apart relative to the
 * overall scale of the data, which suggests that the feature distinguishes classes well.
 */
public final class VarianceBetweenClassesScorer implements FeatureScorer {

  @Override
  public double score(FeatureEvaluationEntry entry) {
    return entry.separation().betweenClassVariance();
  }

  @Override
  public String name() {
    return "varianceBetweenClasses";
  }
}
