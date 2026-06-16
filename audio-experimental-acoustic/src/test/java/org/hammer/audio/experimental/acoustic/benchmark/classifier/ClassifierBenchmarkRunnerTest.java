package org.hammer.audio.experimental.acoustic.benchmark.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.hammer.audio.experimental.acoustic.wingbeat.WingbeatFeatureVector;
import org.hammer.audio.experimental.acoustic.wingbeat.WingbeatLabel;
import org.junit.jupiter.api.Test;

class ClassifierBenchmarkRunnerTest {

  private static final double DELTA = 1e-9;

  private static WingbeatFeatureVector fv(double freqHz) {
    return new WingbeatFeatureVector(
        freqHz, List.of(), List.of(), freqHz, 0.0, 0.0, 0.0, 0.0, 5.0, 1.0, 0.9);
  }

  /** Vectors clearly in the female range [430–550 Hz] → should be classified FEMALE_LIKELY. */
  private static final List<WingbeatFeatureVector> FEMALE_VECTORS =
      List.of(fv(480.0), fv(490.0), fv(500.0), fv(510.0));

  /** Vectors clearly in the male range [600–750 Hz] → should be classified MALE_LIKELY. */
  private static final List<WingbeatFeatureVector> MALE_VECTORS =
      List.of(fv(650.0), fv(660.0), fv(670.0), fv(680.0));

  private static List<WingbeatFeatureVector> allVectors() {
    List<WingbeatFeatureVector> all = new java.util.ArrayList<>();
    all.addAll(FEMALE_VECTORS);
    all.addAll(MALE_VECTORS);
    return all;
  }

  private static List<String> allLabels() {
    List<String> labels = new java.util.ArrayList<>();
    for (int i = 0; i < FEMALE_VECTORS.size(); i++) {
      labels.add(WingbeatLabel.FEMALE_LIKELY);
    }
    for (int i = 0; i < MALE_VECTORS.size(); i++) {
      labels.add(WingbeatLabel.MALE_LIKELY);
    }
    return labels;
  }

  @Test
  void runnerProducesResultForEachBenchmark() {
    ClassifierBenchmarkRunner runner = ClassifierBenchmarkRunner.defaultRunner();

    Map<String, ClassifierBenchmarkResult> results = runner.run(allVectors(), allLabels());

    assertNotNull(results);
    assertEquals(1, results.size());
    assertTrue(results.containsKey("RuleBasedWingbeatClassifier"));
  }

  @Test
  void confusionMatrixHasCorrectTotalCount() {
    ClassifierBenchmarkRunner runner = ClassifierBenchmarkRunner.defaultRunner();
    Map<String, ClassifierBenchmarkResult> results = runner.run(allVectors(), allLabels());

    ConfusionMatrix matrix = results.get("RuleBasedWingbeatClassifier").confusionMatrix();
    assertEquals(allVectors().size(), matrix.totalCount());
  }

  @Test
  void accuracyIsHighForClearlyLabelledData() {
    ClassifierBenchmarkRunner runner = ClassifierBenchmarkRunner.defaultRunner();
    Map<String, ClassifierBenchmarkResult> results = runner.run(allVectors(), allLabels());

    ClassifierBenchmarkResult result = results.get("RuleBasedWingbeatClassifier");
    assertTrue(
        result.confusionMatrix().accuracy() >= 0.5,
        "Accuracy should be at least 0.5 for clearly labelled data");
  }

  @Test
  void confusionMatrixCountsMatchPredictions() {
    // All female vectors → ground truth FEMALE_LIKELY
    // Rule-based classifier classifies 480-510 Hz as FEMALE_LIKELY
    ClassifierBenchmarkRunner runner = ClassifierBenchmarkRunner.defaultRunner();
    Map<String, ClassifierBenchmarkResult> results =
        runner.run(
            FEMALE_VECTORS,
            List.of(
                WingbeatLabel.FEMALE_LIKELY, WingbeatLabel.FEMALE_LIKELY,
                WingbeatLabel.FEMALE_LIKELY, WingbeatLabel.FEMALE_LIKELY));

    ConfusionMatrix matrix = results.get("RuleBasedWingbeatClassifier").confusionMatrix();
    assertEquals(4, matrix.totalCount());
    // All should be predicted as FEMALE_LIKELY → TP = 4
    int tp = matrix.count(WingbeatLabel.FEMALE_LIKELY, WingbeatLabel.FEMALE_LIKELY);
    assertEquals(4, tp);
  }

  @Test
  void macroF1IsInValidRange() {
    ClassifierBenchmarkRunner runner = ClassifierBenchmarkRunner.defaultRunner();
    Map<String, ClassifierBenchmarkResult> results = runner.run(allVectors(), allLabels());

    double macroF1 = results.get("RuleBasedWingbeatClassifier").macroF1();
    assertTrue(macroF1 >= 0.0 && macroF1 <= 1.0);
  }

  @Test
  void customBenchmarkCanBeRegistered() {
    // A trivial "always-unknown" classifier
    ClassifierBenchmark alwaysUnknown =
        (vectors, labels) -> {
          List<String> predicted = new java.util.ArrayList<>();
          for (int i = 0; i < vectors.size(); i++) {
            predicted.add(WingbeatLabel.UNKNOWN);
          }
          return ClassifierBenchmarkResult.of(ConfusionMatrix.of(labels, predicted));
        };
    ClassifierBenchmarkRunner runner = new ClassifierBenchmarkRunner(List.of(alwaysUnknown));

    Map<String, ClassifierBenchmarkResult> results = runner.run(allVectors(), allLabels());

    assertNotNull(results.values().iterator().next());
  }

  @Test
  void labelsIncludePredictedOnlyClasses() {
    ConfusionMatrix matrix =
        ConfusionMatrix.of(
            List.of(WingbeatLabel.FEMALE_LIKELY, WingbeatLabel.FEMALE_LIKELY),
            List.of(WingbeatLabel.UNKNOWN, WingbeatLabel.UNKNOWN));

    assertTrue(matrix.labels().contains(WingbeatLabel.UNKNOWN));
    assertTrue(matrix.toMarkdown().contains("| Actual \\ Predicted | female-likely | unknown |"));
  }

  @Test
  void benchmarkResultKeepsNullMetricsForUnavailableLabels() {
    ConfusionMatrix matrix =
        ConfusionMatrix.of(
            List.of(WingbeatLabel.FEMALE_LIKELY, WingbeatLabel.FEMALE_LIKELY),
            List.of(WingbeatLabel.UNKNOWN, WingbeatLabel.UNKNOWN));

    ClassifierBenchmarkResult result = ClassifierBenchmarkResult.of(matrix);

    assertTrue(result.precisionPerLabel().containsKey(WingbeatLabel.FEMALE_LIKELY));
    assertTrue(result.recallPerLabel().containsKey(WingbeatLabel.UNKNOWN));
    assertNull(result.precisionPerLabel().get(WingbeatLabel.FEMALE_LIKELY));
    assertNull(result.recallPerLabel().get(WingbeatLabel.UNKNOWN));
    assertNull(result.f1PerLabel().get(WingbeatLabel.UNKNOWN));
  }
}
