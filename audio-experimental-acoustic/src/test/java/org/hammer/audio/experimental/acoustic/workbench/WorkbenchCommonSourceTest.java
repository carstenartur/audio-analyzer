package org.hammer.audio.experimental.acoustic.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import org.hammer.audio.acquisition.LocalizationExperiment;
import org.hammer.audio.acquisition.LocalizationInputMode;
import org.hammer.audio.acquisition.MicrophoneArrayLayout;
import org.hammer.audio.acquisition.MicrophoneArrayProfile;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios;
import org.junit.jupiter.api.Test;

class WorkbenchCommonSourceTest {

  @Test
  void replayAndSimulationUseTheSamePipelineAndPreserveExperimentIdentity() throws IOException {
    var scenario = SimulationScenarios.singleSource();
    var profile =
        new MicrophoneArrayProfile(
            "array",
            "Array",
            MicrophoneArrayLayout.CUSTOM,
            scenario.array(),
            Set.of(LocalizationInputMode.SIMULATION, LocalizationInputMode.REPLAY),
            null,
            null);
    var experiment =
        LocalizationExperiment.defined(
            "replay",
            "Replay",
            profile,
            LocalizationInputMode.REPLAY,
            "recording:verified",
            Instant.EPOCH);
    var parameters = WorkbenchParameters.defaults().build();
    var simulation = WorkbenchScenarioRunner.run(scenario, parameters);
    var replay =
        WorkbenchScenarioRunner.runSource(
            scenario.newSource(), scenario, experiment, parameters, null);
    assertEquals(simulation.blockCount(), replay.blockCount());
    assertEquals(simulation.distinctTrackCount(), replay.distinctTrackCount());
    assertEquals("replay", replay.experiment().experimentId());
    assertEquals(LocalizationInputMode.REPLAY, replay.experiment().inputMode());
    assertEquals(null, replay.benchmarkReport(), "A replay has no synthetic ground truth");
  }

  @Test
  void mismatchedSourceAndProfileAreRejectedBeforeProcessing() {
    var scenario = SimulationScenarios.singleSource();
    var profile =
        new MicrophoneArrayProfile(
            "array",
            "Array",
            MicrophoneArrayLayout.CUSTOM,
            new org.hammer.audio.acquisition.MicrophoneArray(
                java.util.List.of(scenario.array().microphone(0))),
            Set.of(LocalizationInputMode.REPLAY),
            null,
            null);
    var experiment =
        LocalizationExperiment.defined(
            "replay",
            "Replay",
            profile,
            LocalizationInputMode.REPLAY,
            "recording:wrong-channels",
            Instant.EPOCH);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WorkbenchScenarioRunner.runSource(
                scenario.newSource(),
                scenario,
                experiment,
                WorkbenchParameters.defaults().build(),
                null));
  }
}
