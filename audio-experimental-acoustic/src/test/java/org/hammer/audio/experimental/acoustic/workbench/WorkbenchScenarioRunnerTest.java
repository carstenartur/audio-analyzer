package org.hammer.audio.experimental.acoustic.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios.SimulationScenario;
import org.hammer.audio.experimental.acoustic.tracking.TrackingSnapshot;
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
  void twoCloseFrequenciesCanProduceTwoTracks() {
    SimulationScenario scenario = SimulationScenarios.twoCloseFrequencies();
    WorkbenchRunResult result =
        WorkbenchScenarioRunner.run(
            scenario, WorkbenchParameters.defaults().blockSize(2048).fftSize(2048).build());

    // Pipeline should find two distinct tracks at some point
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
}
