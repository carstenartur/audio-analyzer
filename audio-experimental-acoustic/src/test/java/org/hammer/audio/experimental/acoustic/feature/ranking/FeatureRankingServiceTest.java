package org.hammer.audio.experimental.acoustic.feature.ranking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.hammer.audio.experimental.acoustic.feature.evaluation.FeatureEvaluationReport;
import org.hammer.audio.experimental.acoustic.feature.evaluation.FeatureEvaluationService;
import org.hammer.audio.experimental.acoustic.wingbeat.WingbeatFeatureVector;
import org.junit.jupiter.api.Test;

class FeatureRankingServiceTest {

  private final FeatureEvaluationService evalService = new FeatureEvaluationService();
  private final FeatureRankingService rankingService = FeatureRankingService.defaultService();

  private static WingbeatFeatureVector fv(double freqHz, double snr) {
    return new WingbeatFeatureVector(
        freqHz, List.of(), List.of(), freqHz, 0.0, 0.0, 0.0, 0.0, snr, 1.0, 0.9);
  }

  @Test
  void rankingContainsAllFeatures() {
    List<WingbeatFeatureVector> vectors =
        List.of(fv(450.0, 5.0), fv(460.0, 6.0), fv(600.0, 8.0), fv(610.0, 7.0));
    List<String> labels = List.of("female-likely", "female-likely", "male-likely", "male-likely");

    FeatureEvaluationReport report = evalService.evaluate(vectors, labels);
    List<FeatureRankingEntry> ranking = rankingService.rank(report);

    assertEquals(report.entries().size(), ranking.size());
  }

  @Test
  void rankingIsSortedByDescendingMeanScore() {
    List<WingbeatFeatureVector> vectors =
        List.of(fv(450.0, 5.0), fv(460.0, 5.0), fv(600.0, 5.0), fv(610.0, 5.0));
    List<String> labels = List.of("female-likely", "female-likely", "male-likely", "male-likely");

    FeatureEvaluationReport report = evalService.evaluate(vectors, labels);
    List<FeatureRankingEntry> ranking = rankingService.rank(report);

    for (int i = 0; i < ranking.size() - 1; i++) {
      assertTrue(
          ranking.get(i).meanScore() >= ranking.get(i + 1).meanScore(),
          "Ranking must be sorted by descending mean score");
    }
  }

  @Test
  void fundamentalFrequencyRanksHighForClearClassSeparation() {
    // fundamentalFrequencyHz perfectly separates the two classes → should rank first
    List<WingbeatFeatureVector> vectors =
        List.of(fv(400.0, 5.0), fv(410.0, 5.0), fv(600.0, 5.0), fv(610.0, 5.0));
    List<String> labels = List.of("female-likely", "female-likely", "male-likely", "male-likely");

    FeatureEvaluationReport report = evalService.evaluate(vectors, labels);
    List<FeatureRankingEntry> ranking = rankingService.rank(report);

    assertFalse(ranking.isEmpty());
    assertEquals("fundamentalFrequencyHz", ranking.get(0).featureName());
  }

  @Test
  void fisherScoreMatchesManualCalculation() {
    // Class 1 (female): freqHz = [400, 400], Class 2 (male): freqHz = [600, 600]
    // global mean = 500, class means = {400, 600}
    // between-class var = 0.5*(400-500)^2 + 0.5*(600-500)^2 = 10000
    // within-class var = 0 (no within-class spread)
    // Fisher ratio → 0 (withinClassVar=0)
    List<WingbeatFeatureVector> vectors =
        List.of(fv(400.0, 5.0), fv(400.0, 5.0), fv(600.0, 5.0), fv(600.0, 5.0));
    List<String> labels = List.of("female-likely", "female-likely", "male-likely", "male-likely");

    FeatureEvaluationReport report = evalService.evaluate(vectors, labels);
    FeatureRankingEntry entry =
        ranking(report).stream()
            .filter(e -> "fundamentalFrequencyHz".equals(e.featureName()))
            .findFirst()
            .orElseThrow();

    double fisherScore = entry.scores().getOrDefault("fisherRatio", -1.0);
    // withinClassVar is 0 → fisherRatio = 0
    assertEquals(0.0, fisherScore, 1e-9);
  }

  @Test
  void customScorerCanBeRegistered() {
    FeatureRankingService custom =
        new FeatureRankingService(List.of(entry -> entry.labelCorrelation()));

    List<WingbeatFeatureVector> vectors = List.of(fv(450.0, 5.0), fv(460.0, 5.0), fv(600.0, 5.0));
    List<String> labels = List.of("female-likely", "female-likely", "male-likely");

    FeatureEvaluationReport report = evalService.evaluate(vectors, labels);
    List<FeatureRankingEntry> ranking = custom.rank(report);

    assertFalse(ranking.isEmpty());
  }

  private List<FeatureRankingEntry> ranking(FeatureEvaluationReport report) {
    return rankingService.rank(report);
  }
}
