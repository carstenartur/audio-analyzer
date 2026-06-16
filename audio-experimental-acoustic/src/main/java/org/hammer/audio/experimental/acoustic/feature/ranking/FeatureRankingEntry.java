package org.hammer.audio.experimental.acoustic.feature.ranking;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Ranking result for a single feature: its name and the score assigned by each registered {@link
 * FeatureScorer}.
 *
 * @param featureName human-readable feature name; must not be blank
 * @param scores map of scorer name to score value; must not be {@code null} or empty
 */
public record FeatureRankingEntry(String featureName, Map<String, Double> scores) {

  /** Validate and defensively copy fields. */
  public FeatureRankingEntry {
    Objects.requireNonNull(featureName, "featureName");
    Objects.requireNonNull(scores, "scores");
    if (featureName.isBlank()) {
      throw new IllegalArgumentException("featureName must not be blank");
    }
    if (scores.isEmpty()) {
      throw new IllegalArgumentException("scores must not be empty");
    }
    scores = Collections.unmodifiableMap(new LinkedHashMap<>(scores));
  }

  /**
   * Return the mean score across all registered scorers. Used as the default sort key in {@link
   * FeatureRankingService}.
   *
   * @return mean score; always {@code >= 0}
   */
  public double meanScore() {
    double sum = 0.0;
    for (double s : scores.values()) {
      sum += s;
    }
    return sum / scores.size();
  }
}
