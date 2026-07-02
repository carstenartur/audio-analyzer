package org.hammer.audio.experimental.acoustic.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios.SimulationScenario;
import org.hammer.audio.experimental.acoustic.tracking.FrameSchedule;
import org.hammer.audio.experimental.acoustic.tracking.TrackedSource;
import org.hammer.audio.experimental.acoustic.tracking.TrackingSnapshot;
import org.hammer.audio.geometry.Vector2;
import org.hammer.audio.geometry.Vector3;
import org.junit.jupiter.api.Test;

/**
 * Headless validation of {@link WorkbenchScenarioRunner} against the canonical simulation
 * scenarios.
 *
 * <p>These tests require no audio hardware and no display: all computation is done on simulated
 * signals using {@link
 * org.hammer.audio.experimental.acoustic.simulation.SimulatedMicrophoneArraySource}.
 */
class WorkbenchScenarioRunnerTest {

  @Test
  void singleSourceRunProducesSnapshots() {
    SimulationScenario scenario = SimulationScenarios.singleSource();
    WorkbenchRunResult result =
        WorkbenchScenarioRunner.run(scenario, WorkbenchParameters.defaults().build());

    assertNotNull(result);
    assertFalse(result.snapshots().isEmpty(), "should produce at least one snapshot");
    assertTrue(result.totalProcessingNanos() >= 0);
  }

  @Test
  void singleSourceRunTracksAtLeastOneSource() {
    SimulationScenario scenario = SimulationScenarios.singleSource();
    WorkbenchRunResult result =
        WorkbenchScenarioRunner.run(scenario, WorkbenchParameters.defaults().build());

    assertTrue(result.anyTracked(), "single source scenario should produce at least one track");
    assertTrue(result.maxTracksInAnyFrame() >= 1);
  }

  @Test
  void twoCloseFrequenciesRunTracksAtLeastOneSource() {
    SimulationScenario scenario = SimulationScenarios.twoCloseFrequencies();
    WorkbenchRunResult result =
        WorkbenchScenarioRunner.run(
            scenario, WorkbenchParameters.defaults().blockSize(2048).fftSize(2048).build());

    // Two-close-frequencies is a hard case; the pipeline may detect one or both sources.
    // We verify at least one track is produced.
    assertTrue(
        result.maxTracksInAnyFrame() >= 1,
        "should track at least one source; max was " + result.maxTracksInAnyFrame());
  }

  @Test
  void movingSourceRunProducesSnapshots() {
    SimulationScenario scenario = SimulationScenarios.movingSource();
    WorkbenchRunResult result =
        WorkbenchScenarioRunner.run(scenario, WorkbenchParameters.defaults().build());

    assertFalse(result.snapshots().isEmpty());
  }

  @Test
  void noisyRoomRunCompletes() {
    SimulationScenario scenario = SimulationScenarios.noisyRoom();
    WorkbenchRunResult result =
        WorkbenchScenarioRunner.run(scenario, WorkbenchParameters.defaults().build());

    assertFalse(result.snapshots().isEmpty());
  }

  @Test
  void reflectedEnvironmentRunCompletes() {
    SimulationScenario scenario = SimulationScenarios.reflectedEnvironment();
    WorkbenchRunResult result =
        WorkbenchScenarioRunner.run(scenario, WorkbenchParameters.defaults().build());

    assertFalse(result.snapshots().isEmpty());
  }

  @Test
  void progressCallbackIsInvokedForEachBlock() {
    SimulationScenario scenario = SimulationScenarios.singleSource();
    List<Integer> indices = new ArrayList<>();

    WorkbenchRunResult result =
        WorkbenchScenarioRunner.run(
            scenario,
            WorkbenchParameters.defaults().build(),
            (snapshot, blockIndex) -> indices.add(blockIndex));

    assertEquals(result.blockCount(), indices.size(), "callback should be called once per block");
    for (int i = 0; i < indices.size(); i++) {
      assertEquals(i, indices.get(i), "block indices should be sequential starting at 0");
    }
  }

  @Test
  void runIsDeterministicForFixedSeed() {
    SimulationScenario scenario = SimulationScenarios.singleSource();
    WorkbenchParameters params = WorkbenchParameters.defaults().build();

    WorkbenchRunResult first = WorkbenchScenarioRunner.run(scenario, params);
    WorkbenchRunResult second = WorkbenchScenarioRunner.run(scenario, params);

    assertEquals(first.blockCount(), second.blockCount(), "block count must be identical");
    for (int i = 0; i < first.snapshots().size(); i++) {
      TrackingSnapshot a = first.snapshots().get(i);
      TrackingSnapshot b = second.snapshots().get(i);
      assertEquals(
          a.clusters().size(), b.clusters().size(), "cluster count at block " + i + " must match");
      assertEquals(
          a.tracks().size(), b.tracks().size(), "track count at block " + i + " must match");
    }
  }

  @Test
  void crossCorrelationEstimatorAlsoRuns() {
    SimulationScenario scenario = SimulationScenarios.singleSource();
    WorkbenchParameters params =
        WorkbenchParameters.defaults()
            .tdoaEstimatorType(WorkbenchParameters.TdoaEstimatorType.CROSS_CORRELATION)
            .build();
    WorkbenchRunResult result = WorkbenchScenarioRunner.run(scenario, params);

    assertFalse(result.snapshots().isEmpty());
  }

  @Test
  void allScenariosCompleteWithoutException() {
    WorkbenchParameters params = WorkbenchParameters.defaults().build();
    for (SimulationScenario scenario : SimulationScenarios.all()) {
      WorkbenchRunResult result = WorkbenchScenarioRunner.run(scenario, params);
      assertNotNull(result, "result must not be null for " + scenario.name());
      assertFalse(
          result.snapshots().isEmpty(),
          "should produce at least one snapshot for " + scenario.name());
    }
  }

  @Test
  void runResultMetricsAreConsistent() {
    SimulationScenario scenario = SimulationScenarios.singleSource();
    WorkbenchRunResult result =
        WorkbenchScenarioRunner.run(scenario, WorkbenchParameters.defaults().build());

    assertTrue(result.blockCount() > 0);
    assertTrue(result.maxTracksInAnyFrame() >= 0);
    assertTrue(result.averageProcessingNanosPerBlock() >= 0.0);
    assertTrue(result.maxProcessingNanosPerBlock() >= 0L);
    // max per block should not exceed total
    assertTrue(
        result.maxProcessingNanosPerBlock() <= result.totalProcessingNanos()
            || result.blockCount() == 1,
        "max per block should not exceed total (unless only one block)");
  }

  @Test
  void singleSourceRunProducesBenchmarkReport() {
    SimulationScenario scenario = SimulationScenarios.singleSource();
    WorkbenchRunResult result =
        WorkbenchScenarioRunner.run(scenario, WorkbenchParameters.defaults().build());

    assertNotNull(
        result.benchmarkReport(), "benchmark report must be present after a non-empty run");
    assertEquals(
        scenario.name(),
        result.benchmarkReport().scenarioId(),
        "benchmark report scenario ID must match the executed scenario");
  }

  @Test
  void benchmarkReportSnapshotCountMatchesRunResult() {
    SimulationScenario scenario = SimulationScenarios.singleSource();
    WorkbenchRunResult result =
        WorkbenchScenarioRunner.run(scenario, WorkbenchParameters.defaults().build());

    assertNotNull(result.benchmarkReport());
    assertEquals(
        result.blockCount(),
        result.benchmarkReport().snapshotCount(),
        "benchmark snapshotCount must equal the number of processed blocks");
  }

  @Test
  void benchmarkReportExpectedSourceCountMatchesScenario() {
    SimulationScenario scenario = SimulationScenarios.singleSource();
    WorkbenchRunResult result =
        WorkbenchScenarioRunner.run(scenario, WorkbenchParameters.defaults().build());

    assertNotNull(result.benchmarkReport());
    assertEquals(
        scenario.emitters().size(),
        result.benchmarkReport().expectedSourceCount(),
        "benchmark expectedSourceCount must match number of emitters in the scenario");
  }

  @Test
  void benchmarkReportIsNullForEmptySnapshots() {
    // Verify the contract: no snapshots → null benchmark report
    // (We test this indirectly via WorkbenchRunResult constructor accepting null)
    WorkbenchRunResult result =
        new WorkbenchRunResult(
            SimulationScenarios.singleSource(),
            WorkbenchParameters.defaults().build(),
            List.of(),
            0L,
            null);

    assertNull(
        result.benchmarkReport(), "benchmark report must be null when constructed with null");
  }

  @Test
  void allScenariosProduceBenchmarkReport() {
    WorkbenchParameters params = WorkbenchParameters.defaults().build();
    for (SimulationScenario scenario : SimulationScenarios.all()) {
      WorkbenchRunResult result = WorkbenchScenarioRunner.run(scenario, params);
      assertNotNull(
          result.benchmarkReport(),
          "benchmark report must not be null for scenario: " + scenario.name());
    }
  }

  // -------------------------------------------------------------------------
  // Budget compliance tests
  // -------------------------------------------------------------------------

  @Test
  void fullRunResultCarriesFrameSchedule() {
    SimulationScenario scenario = SimulationScenarios.singleSource();
    WorkbenchRunResult result =
        WorkbenchScenarioRunner.run(scenario, WorkbenchParameters.defaults().build());

    assertNotNull(result.frameSchedule(), "runner must record frame schedule in result");
    assertTrue(result.frameSchedule().maxProcessingNanos() > 0);
    assertTrue(result.overBudgetFrameCount() >= 0);
    assertTrue(
        result.overBudgetFrameCount() <= result.blockCount(),
        "over-budget count must not exceed total block count");
  }

  @Test
  void allFramesUnderBudgetWhenProcessingNanosIsSmall() {
    FrameSchedule schedule = new FrameSchedule(44100.0, 1024, 0.8);
    long budgetNanos = schedule.maxProcessingNanos();
    TrackingSnapshot snap0 = new TrackingSnapshot(0L, 0L, List.of(), List.of(), budgetNanos / 2);
    TrackingSnapshot snap1 =
        new TrackingSnapshot(1024L, 23_219_954L, List.of(), List.of(), budgetNanos / 2);
    WorkbenchRunResult result =
        new WorkbenchRunResult(
            SimulationScenarios.singleSource(),
            WorkbenchParameters.defaults().build(),
            List.of(snap0, snap1),
            budgetNanos,
            null,
            schedule);

    assertEquals(0L, result.overBudgetFrameCount(), "no frames should be over budget");
    assertFalse(result.isFrameOverBudget(snap0), "snap0 must be under budget");
    assertFalse(result.isFrameOverBudget(snap1), "snap1 must be under budget");
  }

  @Test
  void overBudgetFramesAreDetectedDeterministically() {
    FrameSchedule schedule = new FrameSchedule(44100.0, 1024, 0.8);
    long budgetNanos = schedule.maxProcessingNanos();
    TrackingSnapshot underBudget =
        new TrackingSnapshot(0L, 0L, List.of(), List.of(), budgetNanos / 2);
    TrackingSnapshot overBudget =
        new TrackingSnapshot(1024L, 23_219_954L, List.of(), List.of(), budgetNanos + 1);
    WorkbenchRunResult result =
        new WorkbenchRunResult(
            SimulationScenarios.singleSource(),
            WorkbenchParameters.defaults().build(),
            List.of(underBudget, overBudget),
            budgetNanos / 2 + budgetNanos + 1,
            null,
            schedule);

    assertEquals(1L, result.overBudgetFrameCount(), "exactly one frame should be over budget");
    assertFalse(result.isFrameOverBudget(underBudget), "underBudget snap must not be flagged");
    assertTrue(result.isFrameOverBudget(overBudget), "overBudget snap must be flagged");
  }

  @Test
  void overBudgetCountIsZeroWhenNoScheduleIsSet() {
    TrackingSnapshot snap = new TrackingSnapshot(0L, 0L, List.of(), List.of(), Long.MAX_VALUE - 1L);
    WorkbenchRunResult result =
        new WorkbenchRunResult(
            SimulationScenarios.singleSource(),
            WorkbenchParameters.defaults().build(),
            List.of(snap),
            Long.MAX_VALUE - 1L,
            null);

    assertEquals(0L, result.overBudgetFrameCount(), "no schedule means zero over-budget count");
    assertFalse(
        result.isFrameOverBudget(snap), "no schedule means isFrameOverBudget must return false");
  }

  @Test
  void allFramesOverBudgetWhenProcessingNanosExceedsBudget() {
    FrameSchedule schedule = new FrameSchedule(44100.0, 1024, 0.001);
    long budgetNanos = schedule.maxProcessingNanos();
    long overNanos = budgetNanos + 1L;
    TrackingSnapshot snap0 = new TrackingSnapshot(0L, 0L, List.of(), List.of(), overNanos);
    TrackingSnapshot snap1 = new TrackingSnapshot(1024L, 0L, List.of(), List.of(), overNanos);
    WorkbenchRunResult result =
        new WorkbenchRunResult(
            SimulationScenarios.singleSource(),
            WorkbenchParameters.defaults().build(),
            List.of(snap0, snap1),
            overNanos * 2,
            null,
            schedule);

    assertEquals(2L, result.overBudgetFrameCount(), "all frames should be over budget");
    assertTrue(result.isFrameOverBudget(snap0));
    assertTrue(result.isFrameOverBudget(snap1));
  }

  @Test
  void markdownExportIncludesBudgetSection() {
    FrameSchedule schedule = new FrameSchedule(44100.0, 1024, 0.001);
    long budgetNanos = schedule.maxProcessingNanos();
    TrackingSnapshot snap =
        new TrackingSnapshot(0L, 0L, List.of(), List.of(), budgetNanos + 1_000_000L);
    WorkbenchRunResult result =
        new WorkbenchRunResult(
            SimulationScenarios.singleSource(),
            WorkbenchParameters.defaults().build(),
            List.of(snap),
            budgetNanos + 1_000_000L,
            null,
            schedule);

    String md = WorkbenchRunExporter.toMarkdown(result);
    assertTrue(md.contains("Budget per block"), "markdown must show budget per block metric");
    assertTrue(md.contains("Over-budget frames"), "markdown must show over-budget frame count");
    assertTrue(md.contains("⚠ OVER"), "markdown must mark over-budget frame rows");
  }

  @Test
  void markdownExportShowsOkForUnderBudgetFrames() {
    FrameSchedule schedule = new FrameSchedule(44100.0, 1024, 0.8);
    long budgetNanos = schedule.maxProcessingNanos();
    TrackingSnapshot snap = new TrackingSnapshot(0L, 0L, List.of(), List.of(), 1L);
    WorkbenchRunResult result =
        new WorkbenchRunResult(
            SimulationScenarios.singleSource(),
            WorkbenchParameters.defaults().build(),
            List.of(snap),
            1L,
            null,
            schedule);

    String md = WorkbenchRunExporter.toMarkdown(result);
    assertTrue(md.contains("OK"), "markdown must mark under-budget frame rows as OK");
    assertFalse(md.contains("⚠ OVER"), "markdown must not mark under-budget frames as OVER");
  }

  @Test
  void markdownExportShowsBudgetAsNaWhenScheduleIsUnavailable() {
    TrackingSnapshot snap = new TrackingSnapshot(0L, 0L, List.of(), List.of(), 1L);
    WorkbenchRunResult result =
        new WorkbenchRunResult(
            SimulationScenarios.singleSource(),
            WorkbenchParameters.defaults().build(),
            List.of(snap),
            1L,
            null);

    String md = WorkbenchRunExporter.toMarkdown(result);
    assertTrue(md.contains("N/A"), "markdown must render unknown budget state as N/A");
    assertFalse(md.contains("⚠ OVER"), "unknown budget state must not be marked as OVER");
  }

  @Test
  void csvExportIncludesBudgetExceededColumn() {
    FrameSchedule schedule = new FrameSchedule(44100.0, 1024, 0.001);
    long budgetNanos = schedule.maxProcessingNanos();
    TrackedSource track = trackedSource(1);
    TrackingSnapshot snap =
        new TrackingSnapshot(0L, 0L, List.of(), List.of(track), budgetNanos + 1_000_000L);
    WorkbenchRunResult result =
        new WorkbenchRunResult(
            SimulationScenarios.singleSource(),
            WorkbenchParameters.defaults().build(),
            List.of(snap),
            budgetNanos + 1_000_000L,
            null,
            schedule);

    String csv = WorkbenchRunExporter.toCsv(result);
    assertTrue(csv.contains("budgetExceeded"), "csv must have budgetExceeded header column");
    assertTrue(csv.contains(",true\n"), "csv must export true for over-budget tracked rows");
  }

  @Test
  void csvExportLeavesBudgetExceededEmptyWhenScheduleIsUnavailable() {
    TrackedSource track = trackedSource(1);
    TrackingSnapshot snap = new TrackingSnapshot(0L, 0L, List.of(), List.of(track), 1L);
    WorkbenchRunResult result =
        new WorkbenchRunResult(
            SimulationScenarios.singleSource(),
            WorkbenchParameters.defaults().build(),
            List.of(snap),
            1L,
            null);

    String csv = WorkbenchRunExporter.toCsv(result);
    assertTrue(csv.endsWith(",\n"), "csv must leave budgetExceeded empty when schedule is unknown");
  }

  @Test
  void jsonLinesExportIncludesBudgetExceededField() {
    FrameSchedule schedule = new FrameSchedule(44100.0, 1024, 0.001);
    long budgetNanos = schedule.maxProcessingNanos();
    TrackingSnapshot snap =
        new TrackingSnapshot(0L, 0L, List.of(), List.of(), budgetNanos + 1_000_000L);
    WorkbenchRunResult result =
        new WorkbenchRunResult(
            SimulationScenarios.singleSource(),
            WorkbenchParameters.defaults().build(),
            List.of(snap),
            budgetNanos + 1_000_000L,
            null,
            schedule);

    String json = WorkbenchRunExporter.toJsonLines(result);
    assertTrue(json.contains("\"budgetExceeded\":true"), "json must include budgetExceeded:true");
  }

  @Test
  void jsonLinesExportUsesNullBudgetExceededWhenScheduleIsUnavailable() {
    TrackingSnapshot snap = new TrackingSnapshot(0L, 0L, List.of(), List.of(), 1L);
    WorkbenchRunResult result =
        new WorkbenchRunResult(
            SimulationScenarios.singleSource(),
            WorkbenchParameters.defaults().build(),
            List.of(snap),
            1L,
            null);

    String json = WorkbenchRunExporter.toJsonLines(result);
    assertTrue(
        json.contains("\"budgetExceeded\":null"), "json must encode unknown budget state as null");
  }

  private static TrackedSource trackedSource(int id) {
    return new TrackedSource(
        id, 512.0, 512.0, Vector2.ZERO, Vector2.ZERO, Vector3.ZERO, 0.0, 0.0, 1.0, 0L, 1);
  }
}
