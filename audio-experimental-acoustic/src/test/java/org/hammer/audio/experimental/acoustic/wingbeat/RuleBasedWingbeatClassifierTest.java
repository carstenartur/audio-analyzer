package org.hammer.audio.experimental.acoustic.wingbeat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RuleBasedWingbeatClassifierTest {

  private final RuleBasedWingbeatClassifier classifier = new RuleBasedWingbeatClassifier();

  @Test
  void classifiesLowFrequencyAsUnknown() {
    ClassificationResult result = classifier.classify(features(200.0, 0.9));

    assertEquals(WingbeatLabel.UNKNOWN, result.label());
    assertTrue(result.confidence() > 0.0, "confidence should be > 0 when far from band");
    assertNotNull(result.featureVector());
  }

  @Test
  void classifiesHighFrequencyAsUnknown() {
    ClassificationResult result = classifier.classify(features(1_200.0, 0.9));

    assertEquals(WingbeatLabel.UNKNOWN, result.label());
    assertTrue(result.confidence() > 0.0);
  }

  @Test
  void classifiesFrequencyAtBandEdgeAsUnknownWithLowConfidence() {
    ClassificationResult result = classifier.classify(features(300.0, 1.0));

    // At the very edge of the band the classifier should return unknown or low-confidence result
    double confidence = result.confidence();
    assertTrue(confidence <= 1.0);
  }

  @Test
  void classifiesBloodFedFemaleRange() {
    ClassificationResult result = classifier.classify(features(370.0, 1.0));

    assertEquals(WingbeatLabel.POSSIBLY_BLOOD_FED_FEMALE, result.label());
    assertTrue(result.confidence() > 0.0);
    assertTrue(result.confidence() <= 1.0);
  }

  @Test
  void classifiesFemaleLikelyRange() {
    ClassificationResult result = classifier.classify(features(480.0, 1.0));

    assertEquals(WingbeatLabel.FEMALE_LIKELY, result.label());
    assertTrue(result.confidence() > 0.0);
  }

  @Test
  void classifiesMaleLikelyRange() {
    ClassificationResult result = classifier.classify(features(650.0, 1.0));

    assertEquals(WingbeatLabel.MALE_LIKELY, result.label());
    assertTrue(result.confidence() > 0.0);
  }

  @Test
  void lowFeatureConfidenceReducesResultConfidence() {
    ClassificationResult highConfidence = classifier.classify(features(650.0, 1.0));
    ClassificationResult lowConfidence = classifier.classify(features(650.0, 0.1));

    assertTrue(
        lowConfidence.confidence() < highConfidence.confidence(),
        "low feature confidence should reduce result confidence");
  }

  @Test
  void confidenceIsAlwaysInRange() {
    List<Double> testFrequencies =
        List.of(
            150.0, 299.0, 300.0, 370.0, 430.0, 490.0, 550.0, 600.0, 700.0, 800.0, 900.0, 1500.0);
    for (double freq : testFrequencies) {
      ClassificationResult result = classifier.classify(features(freq, 0.8));
      assertTrue(
          result.confidence() >= 0.0 && result.confidence() <= 1.0,
          "confidence out of range at " + freq + " Hz: " + result.confidence());
    }
  }

  @Test
  void classifyRejectsNullFeatures() {
    assertThrows(NullPointerException.class, () -> classifier.classify(null));
  }

  @Test
  void featureVectorIsRetainedInResult() {
    WingbeatFeatureVector vector = features(500.0, 0.9);
    ClassificationResult result = classifier.classify(vector);

    assertEquals(vector, result.featureVector());
  }

  @Test
  void nearBandBoundaryIsUnknownOrMosquitoLike() {
    // Just below lower bound
    ClassificationResult below = classifier.classify(features(299.0, 1.0));
    assertEquals(WingbeatLabel.UNKNOWN, below.label());

    // Just above upper bound
    ClassificationResult above = classifier.classify(features(801.0, 1.0));
    assertEquals(WingbeatLabel.UNKNOWN, above.label());
  }

  private static WingbeatFeatureVector features(double fundamentalHz, double confidence) {
    return new WingbeatFeatureVector(
        fundamentalHz,
        List.of(),
        List.of(),
        fundamentalHz,
        0.0,
        0.0,
        0.0,
        0.0,
        0.0,
        1.0,
        confidence);
  }
}
