package org.hammer.audio.experimental.acoustic.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hammer.audio.acquisition.LocalizationEngine;
import org.hammer.audio.acquisition.LocalizationExperiment;
import org.hammer.audio.acquisition.LocalizationInputMode;
import org.hammer.audio.acquisition.MicrophoneArrayLayout;
import org.hammer.audio.acquisition.MicrophoneArrayProfile;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios.SimulationScenario;
import org.junit.jupiter.api.Test;

class AcousticLocalizationEngineTest {

  @Test
  void progressReportsTheSameRoundedBudgetAsFinalOverrunCounting() throws Exception {
    var selected = SimulationScenarios.singleSource();
    var scenario =
        new SimulationScenario(
            selected.name(),
            selected.room(),
            selected.array(),
            selected.emitters(),
            44_100.0f,
            0.1,
            selected.randomSeed());
    var profile =
        new MicrophoneArrayProfile(
            "fractional-budget",
            "Fractional budget",
            MicrophoneArrayLayout.CUSTOM,
            scenario.array(),
            Set.of(LocalizationInputMode.SIMULATION),
            null,
            null);
    var experiment =
        LocalizationExperiment.defined(
            "fractional-budget-run",
            "Budget transport",
            profile,
            LocalizationInputMode.SIMULATION,
            "scenario:single-source",
            Instant.EPOCH);
    List<LocalizationEngine.Frame> frames = new ArrayList<>();
    var result =
        new AcousticLocalizationEngine()
            .run(
                scenario.newSource(),
                experiment,
                new LocalizationEngine.Options("single-source", 1024, 1),
                frames::add);

    assertFalse(frames.isEmpty());
    // 1024 / 44100 seconds at the pipeline's 80% budget rounds up, rather than truncating.
    assertTrue(frames.stream().allMatch(frame -> frame.processingBudgetNanos() == 18_575_964L));
    assertEquals(frames.size(), result.blocks());
    assertEquals(
        frames.stream().filter(frame -> frame.processingNanos() > 18_575_964L).count(),
        result.overruns());
    assertTrue(result.benchmarkAvailable());
    assertEquals(
        Set.of("localization.csv", "summary.md", "benchmark.md"), result.requiredArtifacts());
    assertTrue(result.artifacts().keySet().containsAll(result.requiredArtifacts()));
  }

  @Test
  void replayDeclaresLocalizationReportsWithoutRequiringSyntheticBenchmark() throws Exception {
    var scenario = SimulationScenarios.singleSource();
    var engine = new AcousticLocalizationEngine();
    var result =
        engine.run(
            scenario.newSource(),
            replayExperiment(),
            new LocalizationEngine.Options("single-source", 1024, 1),
            frame -> {});

    assertFalse(result.benchmarkAvailable());
    assertEquals(Set.of("localization.csv", "summary.md"), result.requiredArtifacts());
    assertTrue(result.artifacts().keySet().containsAll(result.requiredArtifacts()));
    assertFalse(result.artifacts().containsKey("benchmark.md"));
  }

  @Test
  void requiredArtifactDeclarationSurvivesMissingOutputAndCallerMutation() {
    Set<String> required = new HashSet<>(Set.of("provider-result.txt"));
    var result =
        new LocalizationEngine.Result(replayExperiment(), 1L, 0L, false, required, Map.of());
    required.clear();

    assertEquals(Set.of("provider-result.txt"), result.requiredArtifacts());
    assertTrue(result.artifacts().isEmpty(), "Missing reports must not erase their declaration");
    assertThrows(UnsupportedOperationException.class, () -> result.requiredArtifacts().clear());
  }

  private static LocalizationExperiment replayExperiment() {
    var profile =
        new MicrophoneArrayProfile(
            "replay-contract",
            "Replay contract",
            MicrophoneArrayLayout.CUSTOM,
            SimulationScenarios.singleSource().array(),
            Set.of(LocalizationInputMode.REPLAY),
            null,
            null);
    return LocalizationExperiment.defined(
        "replay-contract-run",
        "Output contract",
        profile,
        LocalizationInputMode.REPLAY,
        "recording:example",
        Instant.EPOCH);
  }
}
