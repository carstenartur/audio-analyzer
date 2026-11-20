package org.hammer.audio.experimental.acoustic.workbench;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.hammer.audio.acquisition.LocalizationEngine;
import org.hammer.audio.acquisition.LocalizationExperiment;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.acquisition.MultiChannelAudioSource;
import org.hammer.audio.experimental.acoustic.plugin.AcousticLocalizationPlugin;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios.SimulationScenario;
import org.hammer.audio.experimental.acoustic.tracking.FrameSchedule;

/** ServiceLoader adapter that exposes the established acoustic runner to any host surface. */
public final class AcousticLocalizationEngine implements LocalizationEngine {

  @Override
  public String id() {
    return new AcousticLocalizationPlugin().descriptor().id();
  }

  @Override
  public String version() {
    return new AcousticLocalizationPlugin().descriptor().version();
  }

  @Override
  public List<String> scenarios() {
    return SimulationScenarios.all().stream().map(SimulationScenario::name).toList();
  }

  @Override
  public MicrophoneArray exampleArray() {
    return SimulationScenarios.singleSource().array();
  }

  @Override
  public float simulationSampleRate(String scenario) {
    return scenario(scenario).sampleRate();
  }

  @Override
  public MultiChannelAudioSource simulate(
      String scenario, MicrophoneArray array, int durationSeconds) {
    return context(scenario, array, durationSeconds, simulationSampleRate(scenario)).newSource();
  }

  @Override
  @SuppressWarnings("PMD.UseConcurrentHashMap") // Local artifact map; sorted, frozen on return.
  public Result run(
      MultiChannelAudioSource source,
      LocalizationExperiment experiment,
      Options options,
      Consumer<Frame> progress)
      throws IOException {
    long processingBudgetNanos =
        new FrameSchedule(
                source.format().sampleRate(),
                options.blockFrames(),
                WorkbenchScenarioRunner.PIPELINE_MAX_LOAD_FRACTION)
            .maxProcessingNanos();
    WorkbenchRunResult result =
        WorkbenchScenarioRunner.runSource(
            source,
            context(
                options.scenario(),
                experiment.profile().array(),
                options.durationSeconds(),
                source.format().sampleRate()),
            experiment,
            WorkbenchParameters.defaults().blockSize(options.blockFrames()).build(),
            (snapshot, index) ->
                progress.accept(
                    new Frame(
                        index,
                        snapshot.processingNanos(),
                        processingBudgetNanos,
                        snapshot.synchronization(),
                        snapshot.tdoaConsistency().reliable(),
                        snapshot.tracks().stream()
                            .map(
                                track ->
                                    new Track(
                                        track.id(),
                                        track.positionMeters().x(),
                                        track.positionMeters().y(),
                                        track.confidence()))
                            .toList())));
    Map<String, String> artifacts = new TreeMap<>();
    artifacts.put("localization.csv", WorkbenchRunExporter.toCsv(result));
    artifacts.put("summary.md", WorkbenchRunExporter.toMarkdown(result));
    if (result.benchmarkReport() != null) {
      artifacts.put("benchmark.md", WorkbenchRunExporter.toBenchmarkMarkdown(result));
    }
    return new Result(
        result.experiment(),
        result.blockCount(),
        result.overBudgetFrameCount(),
        result.benchmarkReport() != null,
        result.benchmarkReport() == null
            ? Set.of("localization.csv", "summary.md")
            : Set.of("localization.csv", "summary.md", "benchmark.md"),
        artifacts);
  }

  private static SimulationScenario context(
      String name, MicrophoneArray array, int duration, float sampleRate) {
    SimulationScenario selected = scenario(name);
    return new SimulationScenario(
        selected.name(),
        selected.room(),
        array,
        selected.emitters(),
        sampleRate,
        duration,
        selected.randomSeed());
  }

  private static SimulationScenario scenario(String name) {
    return SimulationScenarios.all().stream()
        .filter(value -> value.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown simulation: " + name));
  }
}
