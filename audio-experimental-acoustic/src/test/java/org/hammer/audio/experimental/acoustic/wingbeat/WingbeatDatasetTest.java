package org.hammer.audio.experimental.acoustic.wingbeat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WingbeatDatasetTest {

  @Test
  void evaluateComputesAccuracyForPerfectClassifier() {
    WingbeatDataset dataset =
        new WingbeatDataset(
            "test-dataset",
            List.of(
                recording("r1", WingbeatLabel.FEMALE_LIKELY, 480.0),
                recording("r2", WingbeatLabel.MALE_LIKELY, 650.0)));
    RuleBasedWingbeatClassifier classifier = new RuleBasedWingbeatClassifier();

    WingbeatDataset.Evaluation evaluation = dataset.evaluate(classifier);

    assertEquals("test-dataset", evaluation.datasetName());
    assertEquals(2, evaluation.sampleCount());
    assertNotNull(evaluation.accuracy());
    assertTrue(evaluation.accuracy() >= 0.0 && evaluation.accuracy() <= 1.0);
  }

  @Test
  void evaluateCountsCorrectAndTotalSamples() {
    WingbeatClassifier alwaysFemale =
        f -> new ClassificationResult(WingbeatLabel.FEMALE_LIKELY, 0.9);
    WingbeatDataset dataset =
        new WingbeatDataset(
            "counting-dataset",
            List.of(
                recording("r1", WingbeatLabel.FEMALE_LIKELY, 480.0),
                recording("r2", WingbeatLabel.FEMALE_LIKELY, 490.0),
                recording("r3", WingbeatLabel.MALE_LIKELY, 650.0)));

    WingbeatDataset.Evaluation evaluation = dataset.evaluate(alwaysFemale);

    assertEquals(3, evaluation.sampleCount());
    assertEquals(2, evaluation.correctCount());
    assertEquals(2.0 / 3.0, evaluation.accuracy(), 1e-9);
  }

  @Test
  void evaluateWithNoCorrectResultsReturnsZeroAccuracy() {
    WingbeatClassifier alwaysUnknown = f -> ClassificationResult.unknown();
    WingbeatDataset dataset =
        new WingbeatDataset(
            "no-match-dataset", List.of(recording("r1", WingbeatLabel.FEMALE_LIKELY, 480.0)));

    WingbeatDataset.Evaluation evaluation = dataset.evaluate(alwaysUnknown);

    assertEquals(0, evaluation.correctCount());
    assertEquals(0.0, evaluation.accuracy(), 1e-9);
  }

  @Test
  void evaluationAccuracyIsNullForEmptyDataset() {
    WingbeatDataset.Evaluation evaluation = new WingbeatDataset.Evaluation("empty", 0, 0);

    assertNull(evaluation.accuracy());
  }

  @Test
  void datasetConstructorRejectsEmptyEntries() {
    assertThrows(IllegalArgumentException.class, () -> new WingbeatDataset("test", List.of()));
  }

  @Test
  void datasetConstructorRejectsBlankName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new WingbeatDataset("  ", List.of(recording("r1", WingbeatLabel.UNKNOWN, 200.0))));
  }

  @Test
  void evaluateRejectsNullClassifier() {
    WingbeatDataset dataset =
        new WingbeatDataset("test", List.of(recording("r1", WingbeatLabel.UNKNOWN, 200.0)));
    assertThrows(NullPointerException.class, () -> dataset.evaluate(null));
  }

  @Test
  void labelledRecordingConstructorRejectsBlankLabel() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new LabelledRecording("id", "desc", "  ", features(500.0)));
  }

  private static LabelledRecording recording(String id, String label, double frequencyHz) {
    return new LabelledRecording(id, id, label, features(frequencyHz));
  }

  private static WingbeatFeatureVector features(double fundamentalHz) {
    return new WingbeatFeatureVector(
        fundamentalHz, List.of(), List.of(), fundamentalHz, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.9);
  }
}
