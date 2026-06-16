package org.hammer.audio.experimental.acoustic.feature.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.hammer.audio.experimental.acoustic.wingbeat.WingbeatFeatureVector;
import org.junit.jupiter.api.Test;

class FeatureEvaluationServiceTest {

  private static final double DELTA = 1e-9;

  private final FeatureEvaluationService service = new FeatureEvaluationService();

  /** Build a minimal, valid WingbeatFeatureVector with the given fundamental frequency. */
  private static WingbeatFeatureVector fv(double fundamentalHz, double snr, double confidence) {
    return new WingbeatFeatureVector(
        fundamentalHz,
        List.of(),
        List.of(),
        fundamentalHz,
        0.0,
        0.0,
        0.0,
        0.0,
        snr,
        1.0,
        confidence);
  }

  @Test
  void reportContainsAllFeatures() {
    List<WingbeatFeatureVector> vectors = List.of(fv(450.0, 5.0, 0.9), fv(600.0, 8.0, 0.8));
    List<String> labels = List.of("female-likely", "male-likely");

    FeatureEvaluationReport report = service.evaluate(vectors, labels);

    assertNotNull(report);
    List<String> names = report.featureNames();
    assertTrue(names.contains("fundamentalFrequencyHz"));
    assertTrue(names.contains("signalToNoiseRatio"));
    assertTrue(names.contains("featureConfidence"));
    assertEquals(FeatureEvaluationService.featureNames().size(), names.size());
  }

  @Test
  void statisticsMatchKnownValues() {
    // Two vectors with fundamentalFrequencyHz = 400 and 600 → mean = 500, stdDev = 100
    List<WingbeatFeatureVector> vectors = List.of(fv(400.0, 0.0, 1.0), fv(600.0, 0.0, 1.0));
    List<String> labels = List.of("female-likely", "male-likely");

    FeatureEvaluationReport report = service.evaluate(vectors, labels);

    FeatureEvaluationEntry entry = report.entry("fundamentalFrequencyHz").orElseThrow();
    assertEquals(500.0, entry.statistics().mean(), DELTA);
    assertEquals(100.0, entry.statistics().stdDev(), DELTA);
    assertEquals(0, entry.statistics().missingCount());
  }

  @Test
  void separationDetectsClassDifference() {
    // female group: [400, 430], male group: [600, 630] – clear separation
    List<WingbeatFeatureVector> vectors =
        List.of(fv(400.0, 5.0, 1.0), fv(430.0, 5.0, 1.0), fv(600.0, 5.0, 1.0), fv(630.0, 5.0, 1.0));
    List<String> labels = List.of("female-likely", "female-likely", "male-likely", "male-likely");

    FeatureEvaluationReport report = service.evaluate(vectors, labels);
    FeatureEvaluationEntry entry = report.entry("fundamentalFrequencyHz").orElseThrow();

    assertTrue(
        entry.separation().fisherRatio() > 1.0,
        "Fisher ratio should be > 1 for clearly separated classes");
    assertTrue(entry.labelCorrelation() > 0.0, "label correlation should be positive");
  }

  @Test
  void labelCorrelationIsZeroForConstantFeature() {
    // featureConfidence = 0.5 for all → zero variance → correlation = 0
    List<WingbeatFeatureVector> vectors =
        List.of(
            new WingbeatFeatureVector(
                450.0, List.of(), List.of(), 450.0, 0.0, 0.0, 0.0, 0.0, 5.0, 1.0, 0.5),
            new WingbeatFeatureVector(
                600.0, List.of(), List.of(), 600.0, 0.0, 0.0, 0.0, 0.0, 5.0, 1.0, 0.5));
    List<String> labels = List.of("female-likely", "male-likely");

    FeatureEvaluationReport report = service.evaluate(vectors, labels);

    assertEquals(0.0, report.entry("featureConfidence").orElseThrow().labelCorrelation(), DELTA);
  }

  @Test
  void entryByNameReturnsEmptyForUnknownFeature() {
    List<WingbeatFeatureVector> vectors = List.of(fv(500.0, 5.0, 0.9));
    List<String> labels = List.of("unknown");

    FeatureEvaluationReport report = service.evaluate(vectors, labels);

    assertTrue(report.entry("nonExistentFeature").isEmpty());
  }
}
