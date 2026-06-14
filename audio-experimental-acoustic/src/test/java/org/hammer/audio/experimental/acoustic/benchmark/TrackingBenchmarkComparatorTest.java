package org.hammer.audio.experimental.acoustic.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

  @Test
  void compareProducesScenarioGroundedSummaryAndReportFormats() {
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
    MicrophoneArray array = new MicrophoneArray(List.of(new Microphone("m0", Vector2.ZERO, 0)));
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
                array,
                snapshots,
                Map.of("source-0", ClassificationPrediction.ofSpecies("mosquito"))));

    assertEquals(1, report.expectedSourceCount());
    assertEquals(2, report.snapshotCount());
    assertEquals(0.1, report.localization().meanDistanceErrorMeters(), 1e-9);
    assertEquals(0.1, report.localization().medianDistanceErrorMeters(), 1e-9);
    assertEquals(0.0, report.localization().meanAngularErrorDegrees(), 1e-9);
    assertEquals(15.0, report.frequency().meanAbsoluteErrorHz(), 1e-9);
    assertEquals(15.0, report.frequency().medianAbsoluteErrorHz(), 1e-9);
    assertEquals(0.025, report.frequency().meanRelativeError(), 1e-9);
    assertEquals(0.0, report.doppler().meanAbsoluteErrorMetersPerSecond(), 1e-9);
    assertEquals(1.0, report.trackContinuity(), 1e-9);
    assertEquals(1.0, report.idStability(), 1e-9);
    assertEquals(0.5, report.sourceCountAccuracy(), 1e-9);
    assertEquals(0.5, report.meanSourceCountError(), 1e-9);
    assertEquals(1.0 / 3.0, report.falsePositiveRate(), 1e-9);
    assertEquals(0.0, report.falseNegativeRate(), 1e-9);
    assertEquals(1.0, report.classification().accuracy(), 1e-9);
    assertEquals(150L, report.meanProcessingNanos());
    assertEquals(150L, report.medianProcessingNanos());
    assertEquals(200L, report.maxProcessingNanos());
    assertTrue(report.toJson().contains("\"scenarioId\":\"unit-scenario\""));
    assertTrue(report.toCsvRow().contains("\"unit-scenario\""));
    assertTrue(report.toMarkdownSummary().contains("| unit-scenario |"));
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
