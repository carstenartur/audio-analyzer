package org.hammer.audio.experimental.acoustic.wingbeat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Edge-case tests for {@link WingbeatDataset.Evaluation} covering unknown/missing labels, empty
 * datasets, and boundary precision/recall behaviour.
 */
class WingbeatDatasetEvaluationEdgeCasesTest {

  // ── empty confusion matrix (empty Evaluation) ─────────────────────────────

  @Test
  void accuracyIsNullForEmptyDataset() {
    WingbeatDataset.Evaluation ev =
        new WingbeatDataset.Evaluation("empty", 0, 0, Map.of(), Map.of(), Map.of());

    assertNull(ev.accuracy());
  }

  @Test
  void evaluatedAccuracyIsNullForEmptyDataset() {
    WingbeatDataset.Evaluation ev =
        new WingbeatDataset.Evaluation("empty", 0, 0, Map.of(), Map.of(), Map.of());

    assertNull(ev.evaluatedAccuracy());
  }

  @Test
  void groundTruthUnknownCountIsZeroForEmptyDataset() {
    WingbeatDataset.Evaluation ev =
        new WingbeatDataset.Evaluation("empty", 0, 0, Map.of(), Map.of(), Map.of());

    assertEquals(0, ev.groundTruthUnknownCount());
  }

  @Test
  void evaluatedSampleCountIsZeroForEmptyDataset() {
    WingbeatDataset.Evaluation ev =
        new WingbeatDataset.Evaluation("empty", 0, 0, Map.of(), Map.of(), Map.of());

    assertEquals(0, ev.evaluatedSampleCount());
  }

  // ── missing ground truth (UNKNOWN GT label) ───────────────────────────────

  @Test
  void groundTruthUnknownCountReflectsUnknownLabeledSamples() {
    WingbeatClassifier alwaysFemale =
        f -> new ClassificationResult(WingbeatLabel.FEMALE_LIKELY, 0.9);
    WingbeatDataset dataset =
        new WingbeatDataset(
            "mixed",
            List.of(
                recording("r1", WingbeatLabel.FEMALE_LIKELY, 480.0),
                recording("r2", WingbeatLabel.UNKNOWN, 200.0)));

    WingbeatDataset.Evaluation ev = dataset.evaluate(alwaysFemale);

    assertEquals(1, ev.groundTruthUnknownCount());
    assertEquals(1, ev.evaluatedSampleCount());
  }

  @Test
  void evaluatedAccuracyExcludesUnknownGroundTruth() {
    // r1: GT=female, predicted=female (correct)
    // r2: GT=unknown, predicted=female (not evaluable)
    WingbeatClassifier alwaysFemale =
        f -> new ClassificationResult(WingbeatLabel.FEMALE_LIKELY, 0.9);
    WingbeatDataset dataset =
        new WingbeatDataset(
            "mixed",
            List.of(
                recording("r1", WingbeatLabel.FEMALE_LIKELY, 480.0),
                recording("r2", WingbeatLabel.UNKNOWN, 200.0)));

    WingbeatDataset.Evaluation ev = dataset.evaluate(alwaysFemale);

    // 1 correct out of 1 evaluable sample
    assertNotNull(ev.evaluatedAccuracy());
    assertEquals(1.0, ev.evaluatedAccuracy(), 1e-9);
  }

  @Test
  void evaluatedAccuracyIsNullWhenAllSamplesHaveUnknownGroundTruth() {
    WingbeatClassifier alwaysFemale =
        f -> new ClassificationResult(WingbeatLabel.FEMALE_LIKELY, 0.9);
    WingbeatDataset dataset =
        new WingbeatDataset("all-unknown", List.of(recording("r1", WingbeatLabel.UNKNOWN, 300.0)));

    WingbeatDataset.Evaluation ev = dataset.evaluate(alwaysFemale);

    assertNull(ev.evaluatedAccuracy());
  }

  // ── prediction unknown ────────────────────────────────────────────────────

  @Test
  void predictionUnknownCountCountsOnlyKnownGroundTruthRows() {
    WingbeatClassifier alwaysUnknown = f -> ClassificationResult.unknown();
    WingbeatDataset dataset =
        new WingbeatDataset(
            "unknown-pred",
            List.of(
                recording("r1", WingbeatLabel.FEMALE_LIKELY, 480.0),
                recording("r2", WingbeatLabel.MALE_LIKELY, 650.0),
                recording("r3", WingbeatLabel.UNKNOWN, 300.0)));

    WingbeatDataset.Evaluation ev = dataset.evaluate(alwaysUnknown);

    // r1 and r2 have known GT but unknown prediction
    assertEquals(2, ev.predictionUnknownCount());
  }

  @Test
  void predictionUnknownDoesNotCountUnknownGroundTruthRows() {
    WingbeatClassifier alwaysUnknown = f -> ClassificationResult.unknown();
    WingbeatDataset dataset =
        new WingbeatDataset(
            "all-unknown-gt", List.of(recording("r1", WingbeatLabel.UNKNOWN, 300.0)));

    WingbeatDataset.Evaluation ev = dataset.evaluate(alwaysUnknown);

    // The single sample has UNKNOWN GT, so it doesn't contribute to predictionUnknownCount
    assertEquals(0, ev.predictionUnknownCount());
  }

  // ── label never predicted ─────────────────────────────────────────────────

  @Test
  void precisionIsNullForLabelNeverPredicted() {
    WingbeatClassifier alwaysFemale =
        f -> new ClassificationResult(WingbeatLabel.FEMALE_LIKELY, 0.9);
    WingbeatDataset dataset =
        new WingbeatDataset(
            "never-predicted", List.of(recording("r1", WingbeatLabel.MALE_LIKELY, 650.0)));

    WingbeatDataset.Evaluation ev = dataset.evaluate(alwaysFemale);

    assertNull(ev.precision(WingbeatLabel.MALE_LIKELY));
  }

  // ── label never occurs in ground truth ───────────────────────────────────

  @Test
  void recallIsNullForLabelNotInGroundTruth() {
    WingbeatClassifier alwaysFemale =
        f -> new ClassificationResult(WingbeatLabel.FEMALE_LIKELY, 0.9);
    WingbeatDataset dataset =
        new WingbeatDataset(
            "no-male", List.of(recording("r1", WingbeatLabel.FEMALE_LIKELY, 480.0)));

    WingbeatDataset.Evaluation ev = dataset.evaluate(alwaysFemale);

    assertNull(ev.recall(WingbeatLabel.MALE_LIKELY));
  }

  // ── partial dataset ───────────────────────────────────────────────────────

  @Test
  void partialDatasetRecallIsZeroForUnclassifiedLabel() {
    // Classifier always predicts female; male recall must be 0 (not NaN)
    WingbeatClassifier alwaysFemale =
        f -> new ClassificationResult(WingbeatLabel.FEMALE_LIKELY, 0.9);
    WingbeatDataset dataset =
        new WingbeatDataset(
            "partial",
            List.of(
                recording("r1", WingbeatLabel.FEMALE_LIKELY, 480.0),
                recording("r2", WingbeatLabel.MALE_LIKELY, 650.0)));

    WingbeatDataset.Evaluation ev = dataset.evaluate(alwaysFemale);

    Double maleRecall = ev.recall(WingbeatLabel.MALE_LIKELY);
    assertNotNull(maleRecall);
    assertEquals(0.0, maleRecall, 1e-9);
  }

  @Test
  void noNaNOrInfinityInAccuracy() {
    WingbeatClassifier alwaysFemale =
        f -> new ClassificationResult(WingbeatLabel.FEMALE_LIKELY, 0.9);
    WingbeatDataset dataset =
        new WingbeatDataset(
            "check-nan", List.of(recording("r1", WingbeatLabel.FEMALE_LIKELY, 480.0)));

    WingbeatDataset.Evaluation ev = dataset.evaluate(alwaysFemale);

    Double acc = ev.accuracy();
    assertNotNull(acc);
    assertTrue(Double.isFinite(acc));
    Double evAcc = ev.evaluatedAccuracy();
    assertNotNull(evAcc);
    assertTrue(Double.isFinite(evAcc));
  }

  // ── markdown report ───────────────────────────────────────────────────────

  @Test
  void markdownReportContainsEvaluatedAndUnknownCounts() {
    WingbeatClassifier alwaysFemale =
        f -> new ClassificationResult(WingbeatLabel.FEMALE_LIKELY, 0.9);
    WingbeatDataset dataset =
        new WingbeatDataset(
            "report-test",
            List.of(
                recording("r1", WingbeatLabel.FEMALE_LIKELY, 480.0),
                recording("r2", WingbeatLabel.UNKNOWN, 200.0)));

    WingbeatDataset.Evaluation ev = dataset.evaluate(alwaysFemale);
    String report = DatasetWingbeatEvaluationWorkflow.toMarkdownReport(ev);

    assertTrue(report.contains("Evaluated"));
    assertTrue(report.contains("GT unknown"));
    assertTrue(report.contains("Evaluated accuracy"));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static LabelledRecording recording(String id, String label, double frequencyHz) {
    WingbeatFeatureVector features =
        new WingbeatFeatureVector(
            frequencyHz, List.of(), List.of(), frequencyHz, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.9);
    return new LabelledRecording(id, id, label, features);
  }
}
