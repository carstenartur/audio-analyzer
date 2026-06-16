package org.hammer.audio.experimental.acoustic.feature.ranking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.experimental.acoustic.feature.evaluation.FeatureEvaluationEntry;
import org.hammer.audio.experimental.acoustic.feature.evaluation.FeatureEvaluationReport;

/**
 * Ranks features by applying all registered {@link FeatureScorer}s to a {@link
 * FeatureEvaluationReport}.
 *
 * <p>The resulting list is sorted by descending mean score across all scorers. Adding a new scoring
 * strategy requires only implementing {@link FeatureScorer} and passing it to the constructor.
 *
 * <p>This service is stateless and may be called concurrently.
 */
public final class FeatureRankingService {

  private final List<FeatureScorer> scorers;

  /**
   * Create a service with the given scorers.
   *
   * @param scorers list of scorers to apply; must not be {@code null} or empty
   */
  public FeatureRankingService(List<FeatureScorer> scorers) {
    Objects.requireNonNull(scorers, "scorers");
    if (scorers.isEmpty()) {
      throw new IllegalArgumentException("scorers must not be empty");
    }
    this.scorers = List.copyOf(scorers);
  }

  /**
   * Create a service with the three built-in scorers: {@link VarianceBetweenClassesScorer}, {@link
   * FisherScorer}, and {@link InformationGainScorer}.
   *
   * @return default service instance; never {@code null}
   */
  public static FeatureRankingService defaultService() {
    return new FeatureRankingService(
        List.of(
            new VarianceBetweenClassesScorer(), new FisherScorer(), new InformationGainScorer()));
  }

  /**
   * Rank all features in the report by descending mean score.
   *
   * @param report feature evaluation report; must not be {@code null}
   * @return sorted list of ranking entries, best features first; never {@code null}
   */
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  public List<FeatureRankingEntry> rank(FeatureEvaluationReport report) {
    Objects.requireNonNull(report, "report");

    List<FeatureRankingEntry> entries = new ArrayList<>(report.entries().size());
    for (FeatureEvaluationEntry evalEntry : report.entries()) {
      Map<String, Double> scores = new LinkedHashMap<>();
      for (FeatureScorer scorer : scorers) {
        scores.put(scorer.name(), scorer.score(evalEntry));
      }
      entries.add(new FeatureRankingEntry(evalEntry.featureName(), scores));
    }

    entries.sort((a, b) -> Double.compare(b.meanScore(), a.meanScore()));
    return List.copyOf(entries);
  }
}
