package org.hammer.audio.experimental.acoustic.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.hammer.audio.acquisition.Microphone;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.experimental.acoustic.scenario.AcousticGroundTruth;
import org.hammer.audio.experimental.acoustic.scenario.ClassificationGroundTruth;
import org.hammer.audio.experimental.acoustic.scenario.Scenario;
import org.hammer.audio.experimental.acoustic.scenario.ScenarioEnvironment;
import org.hammer.audio.experimental.acoustic.scenario.ScenarioSource;
import org.hammer.audio.experimental.acoustic.scenario.ScenarioTrajectory;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios;
import org.hammer.audio.experimental.acoustic.tracking.TrackedSource;
import org.hammer.audio.experimental.acoustic.tracking.TrackingSnapshot;
import org.hammer.audio.geometry.Vector2;
import org.hammer.audio.geometry.Vector3;
import org.junit.jupiter.api.Test;

class TrackingBenchmarkComparatorTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final MicrophoneArray ARRAY =
      new MicrophoneArray(List.of(new Microphone("m0", Vector2.ZERO, 0)));

  @Test
  void compareProducesScenarioGroundedSummaryAndReportFormats() throws Exception {
    Scenario scenario =
        new Scenario(
            "unit-scenario",
            "Unit benchmark scenario",
            List.of(
                ScenarioSource.builder("source-0", "mosquito")
                    .trajectory(
                        ScenarioTrajectory.linear(new Vector2(1.0, 0.0), Vector2.ZERO, 1.0, 2))
                    .acousticProperties(AcousticGroundTruth.ofFrequency(600.0))
                    .labels(ClassificationGroundTruth.ofSpecies("mosquito"))
                    .build()),
            ScenarioEnvironment.DEFAULT);
    List<TrackingSnapshot> snapshots =
        List.of(
            new TrackingSnapshot(
                0, 0L, List.of(), List.of(track(7, 630.0, new Vector2(1.2, 0.0), 0L)), 100L),
            new TrackingSnapshot(
                1,
                1_000_000_000L,
                List.of(),
                List.of(
                    track(7, 600.0, new Vector2(1.0, 0.0), 1L),
                    track(9, 1_500.0, new Vector2(3.0, 0.0), 1L)),
                200L));

    TrackingBenchmarkComparator comparator = new TrackingBenchmarkComparator();
    BenchmarkReport report =
        comparator.compare(
            scenario,
            new BenchmarkMeasurements(
                ARRAY,
                snapshots,
                Map.of("source-0", ClassificationPrediction.ofSpecies("mosquito"))));

    assertEquals(1, report.expectedSourceCount());
    assertEquals(2, report.snapshotCount());
    assertEquals(0.1, report.localization().meanDistanceErrorMeters(), 1e-9);
    assertEquals(0.1, report.localization().medianDistanceErrorMeters(), 1e-9);
    assertEquals(0.0, report.localization().meanAngularErrorDegrees(), 1e-9);
    assertEquals(2, report.localization().sampleCount());
    assertEquals(2, report.localization().evaluatedCount());
    assertEquals(0, report.localization().skippedCount());
    assertEquals(0, report.localization().unavailableTruthCount());
    assertEquals(15.0, report.frequency().meanAbsoluteErrorHz(), 1e-9);
    assertEquals(15.0, report.frequency().medianAbsoluteErrorHz(), 1e-9);
    assertEquals(0.025, report.frequency().meanRelativeError(), 1e-9);
    assertEquals(0.0, report.doppler().meanAbsoluteErrorMetersPerSecond(), 1e-9);
    assertEquals(2, report.doppler().evaluatedCount());
    assertEquals(1.0, report.trackContinuity(), 1e-9);
    assertEquals(2, report.trackContinuitySampleCount());
    assertEquals(2, report.trackContinuityEvaluatedCount());
    assertEquals(0, report.trackContinuitySkippedCount());
    assertEquals(0, report.trackContinuityUnavailableTruthCount());
    assertEquals(1.0, report.idStability(), 1e-9);
    assertEquals(0.5, report.sourceCountAccuracy(), 1e-9);
    assertEquals(0.5, report.meanSourceCountError(), 1e-9);
    assertEquals(1.0 / 3.0, report.falsePositiveRate(), 1e-9);
    assertEquals(0.0, report.falseNegativeRate(), 1e-9);
    assertEquals(1.0, report.classification().accuracy(), 1e-9);
    assertEquals(1, report.classification().evaluatedCount());
    assertEquals(150L, report.meanProcessingNanos());
    assertEquals(150L, report.medianProcessingNanos());
    assertEquals(200L, report.maxProcessingNanos());

    JsonNode json = OBJECT_MAPPER.readTree(report.toJson());
    assertEquals("unit-scenario", json.get("scenarioId").asText());
    assertEquals(2, json.get("trackContinuityEvaluatedCount").asInt());
    assertTrue(report.toCsvRow().contains("\"unit-scenario\""));
    assertTrue(report.toMarkdownSummary().contains("| unit-scenario |"));
    assertTrue(report.toMarkdownSummary().contains("1.000000 (2/2)"));
  }

  @Test
  void alignmentIsDeterministicForReorderedSourcesAndTracks() {
    Scenario sourceOrderA =
        scenario(
            "alignment-order-a",
            source("alpha", new Vector2(1.0, 0.0), Vector2.ZERO, 400.0, null),
            source("beta", new Vector2(1.2, 0.1), Vector2.ZERO, 700.0, null));
    Scenario sourceOrderB =
        scenario(
            "alignment-order-b",
            source("beta", new Vector2(1.2, 0.1), Vector2.ZERO, 700.0, null),
            source("alpha", new Vector2(1.0, 0.0), Vector2.ZERO, 400.0, null));
    TrackingSnapshot reversedTracks =
        new TrackingSnapshot(
            0,
            0L,
            List.of(),
            List.of(
                track(20, 699.0, new Vector2(1.18, 0.08), 0L),
                track(10, 401.0, new Vector2(1.01, 0.01), 0L)),
            5L);

    SnapshotGroundTruthAligner aligner = new SnapshotGroundTruthAligner();

    Map<String, Integer> mappingA = sourceToTrackIds(aligner.align(sourceOrderA, reversedTracks));
    Map<String, Integer> mappingB = sourceToTrackIds(aligner.align(sourceOrderB, reversedTracks));

    assertEquals(Map.of("alpha", 10, "beta", 20), mappingA);
    assertEquals(mappingA, mappingB);
  }

  @Test
  void alignmentReportsMissingAndSpuriousTracks() {
    Scenario scenario =
        scenario(
            "alignment-gaps",
            source("alpha", new Vector2(1.0, 0.0), Vector2.ZERO, 400.0, null),
            source("beta", new Vector2(2.0, 0.0), Vector2.ZERO, 700.0, null));
    TrackingSnapshot snapshot =
        new TrackingSnapshot(
            0,
            0L,
            List.of(),
            List.of(
                track(1, 400.0, new Vector2(1.0, 0.0), 0L),
                track(9, 1_300.0, new Vector2(4.0, 0.0), 0L)),
            10L);

    SnapshotAlignment alignment = new SnapshotGroundTruthAligner().align(scenario, snapshot);

    assertEquals(Map.of("alpha", 1), sourceToTrackIds(alignment));
    assertEquals(
        List.of("beta"),
        alignment.missingSources().stream().map(o -> o.source().sourceId()).toList());
    assertEquals(List.of(9), alignment.spuriousTracks().stream().map(TrackedSource::id).toList());
  }

  @Test
  void compareNormalizesSnapshotTimestampsFromFirstObservation() {
    Scenario scenario =
        scenario(
            "absolute-timestamps",
            source("alpha", new Vector2(2.0, 0.0), Vector2.ZERO, 400.0, null));

    BenchmarkReport report =
        new TrackingBenchmarkComparator()
            .compare(
                scenario,
                new BenchmarkMeasurements(
                    ARRAY,
                    List.of(
                        new TrackingSnapshot(
                            0,
                            10_000_000_000L,
                            List.of(),
                            List.of(track(1, 400.0, new Vector2(2.0, 0.0), 10_000_000_000L)),
                            10L),
                        new TrackingSnapshot(
                            1,
                            11_000_000_000L,
                            List.of(),
                            List.of(track(1, 400.0, new Vector2(2.0, 0.0), 11_000_000_000L)),
                            10L)),
                    Map.of()));

    assertEquals(2, report.localization().evaluatedCount());
    assertEquals(0.0, report.localization().meanDistanceErrorMeters(), 1e-9);
    assertEquals(1.0, report.trackContinuity(), 1e-9);
  }

  @Test
  void compareDistinguishesSkippedAndUnavailableTruth() {
    Scenario scenario =
        scenario(
            "partial-truth",
            source(
                "known",
                positionsOnlyTrajectory(new Vector2(2.0, 0.0), new Vector2(2.5, 0.0)),
                AcousticGroundTruth.ofFrequency(500.0),
                ClassificationGroundTruth.ofSpecies("mosquito")),
            ScenarioSource.builder("unknown", "mosquito")
                .labels(ClassificationGroundTruth.unknown())
                .build());

    BenchmarkReport report =
        new TrackingBenchmarkComparator()
            .compare(
                scenario,
                new BenchmarkMeasurements(
                    ARRAY,
                    List.of(
                        new TrackingSnapshot(
                            0,
                            0L,
                            List.of(),
                            List.of(track(5, 900.0, new Vector2(9.0, 9.0), 0L)),
                            25L)),
                    Map.of()));

    assertNull(report.localization().meanDistanceErrorMeters());
    assertEquals(2, report.localization().sampleCount());
    assertEquals(0, report.localization().evaluatedCount());
    assertEquals(1, report.localization().skippedCount());
    assertEquals(1, report.localization().unavailableTruthCount());

    assertNull(report.frequency().meanAbsoluteErrorHz());
    assertEquals(2, report.frequency().sampleCount());
    assertEquals(0, report.frequency().evaluatedCount());
    assertEquals(1, report.frequency().skippedCount());
    assertEquals(1, report.frequency().unavailableTruthCount());

    assertNull(report.doppler().meanAbsoluteErrorMetersPerSecond());
    assertEquals(2, report.doppler().sampleCount());
    assertEquals(0, report.doppler().evaluatedCount());
    assertEquals(0, report.doppler().skippedCount());
    assertEquals(2, report.doppler().unavailableTruthCount());

    assertNull(report.classification().accuracy());
    assertEquals(2, report.classification().sampleCount());
    assertEquals(0, report.classification().evaluatedCount());
    assertEquals(1, report.classification().skippedCount());
    assertEquals(1, report.classification().unavailableTruthCount());

    assertEquals(0.0, report.trackContinuity(), 1e-9);
    assertEquals(2, report.trackContinuitySampleCount());
    assertEquals(0, report.trackContinuityEvaluatedCount());
    assertEquals(1, report.trackContinuitySkippedCount());
    assertEquals(1, report.trackContinuityUnavailableTruthCount());
    assertEquals(1.0, report.falsePositiveRate(), 1e-9);
    assertEquals(1.0, report.falseNegativeRate(), 1e-9);
    assertEquals(0.0, report.idStability(), 1e-9);
  }

  @Test
  void reportSerializationEscapesSpecialCharacters() throws Exception {
    BenchmarkReport report =
        new BenchmarkReport(
            "weird \"scenario\", ßeta \\\\ path | pipe",
            1,
            1,
            new LocalizationErrorMetric(0.5, 0.5, 1.0, 1.0, 1, 1, 0, 0),
            new FrequencyErrorMetric(2.0, 2.0, 0.01, 1, 1, 0, 0),
            new DopplerErrorMetric(0.25, 0.25, 1, 1, 0, 0),
            new ClassificationAccuracyMetric(1.0, 1, 1, 1, 0, 0),
            1.0,
            1,
            1,
            0,
            0,
            1.0,
            1.0,
            0.0,
            0.0,
            0.0,
            10L,
            10L,
            10L);

    JsonNode json = OBJECT_MAPPER.readTree(report.toJson());
    assertEquals("weird \"scenario\", ßeta \\\\ path | pipe", json.get("scenarioId").asText());

    String csvRow = report.toCsvRow();
    assertTrue(csvRow.startsWith("\"weird \"\"scenario\"\", ßeta \\\\ path | pipe\""));
    assertFalse(csvRow.contains("\"scenario\", ßeta"));

    String markdown = report.toMarkdownSummary();
    assertTrue(markdown.contains("weird \"scenario\", ßeta"));
    assertTrue(markdown.contains("\\| pipe"));
  }

  @Test
  void compareHandlesEmptyBenchmarkRuns() {
    Scenario scenario =
        scenario("empty-run", source("alpha", new Vector2(1.0, 0.0), Vector2.ZERO, 400.0, null));

    BenchmarkReport report =
        new TrackingBenchmarkComparator()
            .compare(scenario, BenchmarkMeasurements.of(ARRAY, List.of()));

    assertNull(report.trackContinuity());
    assertEquals(0, report.trackContinuitySampleCount());
    assertNull(report.idStability());
    assertNull(report.sourceCountAccuracy());
    assertNull(report.meanSourceCountError());
    assertNull(report.falsePositiveRate());
    assertNull(report.falseNegativeRate());
    assertEquals(0, report.localization().sampleCount());
    assertEquals(0, report.frequency().sampleCount());
    assertEquals(0, report.doppler().sampleCount());
  }

  @Test
  void classificationComparisonDoesNotThrowWhenPredictionOmitsCustomLabel() {
    Scenario scenario =
        scenario(
            "classification-label-gap",
            ScenarioSource.builder("alpha", "mosquito")
                .trajectory(ScenarioTrajectory.linear(new Vector2(1.0, 0.0), Vector2.ZERO, 1.0, 2))
                .acousticProperties(AcousticGroundTruth.ofFrequency(400.0))
                .labels(ClassificationGroundTruth.synthetic("fixture-a"))
                .build());

    BenchmarkReport report =
        new TrackingBenchmarkComparator()
            .compare(
                scenario,
                new BenchmarkMeasurements(
                    ARRAY,
                    List.of(
                        new TrackingSnapshot(
                            0,
                            0L,
                            List.of(),
                            List.of(track(1, 400.0, new Vector2(1.0, 0.0), 0L)),
                            10L)),
                    Map.of("alpha", ClassificationPrediction.unknown())));

    assertNotNull(report);
    assertEquals(0.0, report.classification().accuracy(), 1e-9);
    assertEquals(1, report.classification().evaluatedCount());
  }

  @Test
  void runnerBenchmarksAllSimulationScenarios() {
    TrackingScenarioBenchmarkRunner runner = new TrackingScenarioBenchmarkRunner(2048);
    List<BenchmarkReport> reports = runner.runAll();

    assertEquals(SimulationScenarios.all().size(), reports.size());
    for (BenchmarkReport report : reports) {
      assertTrue(
          report.snapshotCount() > 0, () -> report.scenarioId() + " should produce snapshots");
      assertTrue(
          report.expectedSourceCount() > 0,
          () -> report.scenarioId() + " should expose expected sources");
      assertTrue(
          report.meanProcessingNanos() >= 0L,
          () -> report.scenarioId() + " should report processing time");
    }
  }

  private static Scenario scenario(String id, ScenarioSource... sources) {
    return new Scenario(id, id, List.of(sources), ScenarioEnvironment.DEFAULT);
  }

  private static ScenarioSource source(
      String sourceId,
      Vector2 startPosition,
      Vector2 velocity,
      double frequencyHz,
      ClassificationGroundTruth classificationGroundTruth) {
    return ScenarioSource.builder(sourceId, "mosquito")
        .trajectory(ScenarioTrajectory.linear(startPosition, velocity, 1.0, 2))
        .acousticProperties(AcousticGroundTruth.ofFrequency(frequencyHz))
        .labels(classificationGroundTruth)
        .build();
  }

  private static ScenarioSource source(
      String sourceId,
      ScenarioTrajectory trajectory,
      AcousticGroundTruth acousticGroundTruth,
      ClassificationGroundTruth classificationGroundTruth) {
    return ScenarioSource.builder(sourceId, "mosquito")
        .trajectory(trajectory)
        .acousticProperties(acousticGroundTruth)
        .labels(classificationGroundTruth)
        .build();
  }

  private static ScenarioTrajectory positionsOnlyTrajectory(Vector2 first, Vector2 second) {
    return new ScenarioTrajectory(List.of(0.0, 1.0), List.of(first, second), null, null);
  }

  private static Map<String, Integer> sourceToTrackIds(SnapshotAlignment alignment) {
    return alignment.matchedSources().stream()
        .collect(
            java.util.stream.Collectors.toMap(
                observation -> observation.groundTruth().source().sourceId(),
                observation -> observation.trackedSource().id()));
  }

  private static TrackedSource track(
      int id, double frequencyHz, Vector2 positionMeters, long frameIndex) {
    return new TrackedSource(
        id,
        frequencyHz,
        frequencyHz,
        positionMeters,
        Vector2.ZERO,
        Vector3.ZERO,
        0.0,
        0.0,
        1.0,
        frameIndex,
        1);
  }
}
