package org.hammer.audio.experimental.acoustic.workbench;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.hammer.audio.experimental.acoustic.benchmark.BenchmarkReport;
import org.hammer.audio.experimental.acoustic.benchmark.ClassificationAccuracyMetric;
import org.hammer.audio.experimental.acoustic.benchmark.DopplerErrorMetric;
import org.hammer.audio.experimental.acoustic.benchmark.FrequencyErrorMetric;
import org.hammer.audio.experimental.acoustic.benchmark.LocalizationErrorMetric;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios.SimulationScenario;
import org.junit.jupiter.api.Test;

/**
 * Headless construction and lifecycle tests for {@link AcousticLocalizationWorkbenchPanel}.
 *
 * <p>All tests use {@code java.awt.GraphicsEnvironment.isHeadless()} awareness: the panel can be
 * constructed in headless environments because Swing construction itself is headless-safe when no
 * window is shown. The tests do not open a visible frame.
 */
class AcousticLocalizationWorkbenchPanelTest {

  @Test
  void panelCanBeConstructedHeadless() {
    AcousticLocalizationWorkbenchPanel panel = new AcousticLocalizationWorkbenchPanel();
    assertNotNull(panel, "panel must not be null");
  }

  @Test
  void panelHasNoResultBeforeRun() {
    AcousticLocalizationWorkbenchPanel panel = new AcousticLocalizationWorkbenchPanel();
    assertNull(panel.lastResult(), "lastResult() should be null before any run");
  }

  @Test
  void roomMapPanelCanBeConstructed() {
    AcousticLocalizationWorkbenchPanel.RoomMapPanel map =
        new AcousticLocalizationWorkbenchPanel.RoomMapPanel();
    assertNotNull(map);
    assertNotNull(map.getPreferredSize());
  }

  @Test
  void roomMapPanelClearAndSetResultDoNotThrow() {
    AcousticLocalizationWorkbenchPanel.RoomMapPanel map =
        new AcousticLocalizationWorkbenchPanel.RoomMapPanel();
    SimulationScenario scenario = SimulationScenarios.singleSource();
    map.clear(scenario);

    WorkbenchRunResult result =
        WorkbenchScenarioRunner.run(scenario, WorkbenchParameters.defaults().build());
    map.setResult(result);
  }

  @Test
  void headlessScenarioRunProducesResultUsableByPanel() {
    // This is the key acceptance-criteria test: run headlessly and verify that the
    // result satisfies the structural guarantees required by the workbench panel.
    SimulationScenario scenario = SimulationScenarios.singleSource();
    WorkbenchRunResult result =
        WorkbenchScenarioRunner.run(scenario, WorkbenchParameters.defaults().build());

    assertFalse(result.snapshots().isEmpty(), "run must produce at least one snapshot");
    assertTrue(result.totalProcessingNanos() >= 0);

    // Verify that the export methods work without throwing
    String md = WorkbenchRunExporter.toMarkdown(result);
    assertFalse(md.isBlank(), "markdown export must not be blank");
    assertTrue(md.contains(scenario.name()), "markdown must contain the scenario name");

    String csv = WorkbenchRunExporter.toCsv(result);
    assertFalse(csv.isBlank(), "csv export must have at least a header");
    assertTrue(csv.startsWith("frameIndex,"), "csv must start with header");

    String json = WorkbenchRunExporter.toJsonLines(result);
    assertFalse(json.isBlank(), "json export must not be blank");
  }

  @Test
  void multipleRequiredScenariosRunHeadless() {
    // Acceptance criterion: singleSource, twoCloseFrequencies, movingSource,
    // noisyRoom and reflectedEnvironment must all run from the workbench.
    SimulationScenario[] required = {
      SimulationScenarios.singleSource(),
      SimulationScenarios.twoCloseFrequencies(),
      SimulationScenarios.movingSource(),
      SimulationScenarios.noisyRoom(),
      SimulationScenarios.reflectedEnvironment()
    };
    WorkbenchParameters params = WorkbenchParameters.defaults().build();
    for (SimulationScenario scenario : required) {
      WorkbenchRunResult result = WorkbenchScenarioRunner.run(scenario, params);
      assertFalse(
          result.snapshots().isEmpty(),
          "scenario " + scenario.name() + " must produce at least one snapshot");
    }
  }

  @Test
  void exporterObservedTrackIdsMatchesResult() {
    SimulationScenario scenario = SimulationScenarios.singleSource();
    WorkbenchRunResult result =
        WorkbenchScenarioRunner.run(scenario, WorkbenchParameters.defaults().build());

    java.util.List<Integer> ids = WorkbenchRunExporter.observedTrackIds(result);
    assertNotNull(ids);
    // IDs returned should be a subset of IDs seen in snapshots
    java.util.Set<Integer> allIds = new java.util.HashSet<>();
    for (var snap : result.snapshots()) {
      for (var track : snap.tracks()) {
        allIds.add(track.id());
      }
    }
    for (int id : ids) {
      assertTrue(
          allIds.contains(id), "observedTrackIds returned id " + id + " not seen in snapshots");
    }
  }

  @Test
  void benchmarkMarkdownExportIsNonBlankAfterRun() {
    SimulationScenario scenario = SimulationScenarios.singleSource();
    WorkbenchRunResult result =
        WorkbenchScenarioRunner.run(scenario, WorkbenchParameters.defaults().build());

    String bm = WorkbenchRunExporter.toBenchmarkMarkdown(result);
    assertFalse(bm.isBlank(), "benchmark markdown must not be blank after a successful run");
    assertTrue(bm.contains(scenario.name()), "benchmark markdown must contain the scenario name");
  }

  @Test
  void benchmarkMarkdownExportContainsLocalizationSection() {
    SimulationScenario scenario = SimulationScenarios.singleSource();
    WorkbenchRunResult result =
        WorkbenchScenarioRunner.run(scenario, WorkbenchParameters.defaults().build());

    String bm = WorkbenchRunExporter.toBenchmarkMarkdown(result);
    assertTrue(bm.contains("Localization"), "benchmark markdown must contain localization section");
    assertTrue(
        bm.contains("Tracking Quality"),
        "benchmark markdown must contain tracking quality section");
  }

  @Test
  void benchmarkMarkdownForNullReportProducesPlaceholder() {
    WorkbenchRunResult result =
        new WorkbenchRunResult(
            SimulationScenarios.singleSource(),
            WorkbenchParameters.defaults().build(),
            List.of(),
            0L,
            null);

    String bm = WorkbenchRunExporter.toBenchmarkMarkdown(result);
    assertFalse(bm.isBlank(), "benchmark markdown placeholder must not be blank");
    assertTrue(
        bm.contains("Not available"),
        "benchmark markdown placeholder must indicate unavailability");
  }

  @Test
  void roomMapPanelSetResultAccumulatesTrackHistory() {
    SimulationScenario scenario = SimulationScenarios.singleSource();
    WorkbenchRunResult result =
        WorkbenchScenarioRunner.run(scenario, WorkbenchParameters.defaults().build());

    AcousticLocalizationWorkbenchPanel.RoomMapPanel map =
        new AcousticLocalizationWorkbenchPanel.RoomMapPanel();
    // Should not throw and should handle track history accumulation silently
    map.setResult(result);
    assertNotNull(map.getPreferredSize(), "panel should still be valid after setResult");
  }

  @Test
  void groundTruthAndEstimatedTracksCanBeCompared() {
    // Acceptance criterion: run produces a result from which ground-truth and estimated
    // track data are both accessible and can be compared.
    SimulationScenario scenario = SimulationScenarios.movingSource();
    WorkbenchRunResult result =
        WorkbenchScenarioRunner.run(scenario, WorkbenchParameters.defaults().build());

    // Ground truth is accessible via scenario
    assertFalse(
        result.scenario().groundTruth().sources().isEmpty(),
        "ground truth must have at least one source");

    // Estimated tracks are accessible via snapshots
    assertFalse(result.snapshots().isEmpty(), "run must produce snapshots");

    // Benchmark report provides the comparison
    assertNotNull(result.benchmarkReport(), "benchmark report must be present");
    assertNotNull(
        result.benchmarkReport().localization(),
        "localization metrics must be present in benchmark report");
  }

  @Test
  void benchmarkMarkdownRendersNaForZeroEvaluatedMetrics() {
    // Exercises the formatMetric helper path where evaluatedCount == 0 causes null metric values.
    LocalizationErrorMetric localization =
        LocalizationErrorMetric.ofSamples(List.of(), List.of(), 0, 0);
    FrequencyErrorMetric frequency = FrequencyErrorMetric.ofSamples(List.of(), List.of(), 0, 0);
    DopplerErrorMetric doppler = DopplerErrorMetric.ofSamples(List.of(), 0, 0);
    ClassificationAccuracyMetric classification = ClassificationAccuracyMetric.ofCounts(0, 0, 0, 0);
    BenchmarkReport report =
        new BenchmarkReport(
            "test-scenario",
            1,
            0,
            localization,
            frequency,
            doppler,
            classification,
            null,
            0,
            0,
            0,
            0,
            null,
            null,
            null,
            null,
            null,
            0L,
            0L,
            0L);

    SimulationScenario scenario = SimulationScenarios.singleSource();
    WorkbenchRunResult result =
        new WorkbenchRunResult(
            scenario, WorkbenchParameters.defaults().build(), List.of(), 0L, report);

    String bm = WorkbenchRunExporter.toBenchmarkMarkdown(result);
    assertFalse(bm.isBlank(), "benchmark markdown must not be blank");
    // Localization section: all three metric rows must render "n/a" when evaluatedCount == 0
    assertTrue(
        bm.contains("Mean position error (m) | n/a"),
        "mean position error must render 'n/a' for zero-evaluated localization");
    assertTrue(
        bm.contains("Median position error (m) | n/a"),
        "median position error must render 'n/a' for zero-evaluated localization");
    assertTrue(
        bm.contains("Mean angular error (°) | n/a"),
        "mean angular error must render 'n/a' for zero-evaluated localization");
    // Frequency section: all three metric rows must render "n/a" when evaluatedCount == 0
    assertTrue(
        bm.contains("Mean absolute error (Hz) | n/a"),
        "mean absolute error must render 'n/a' for zero-evaluated frequency");
    assertTrue(
        bm.contains("Median absolute error (Hz) | n/a"),
        "median absolute error must render 'n/a' for zero-evaluated frequency");
    assertTrue(
        bm.contains("Mean relative error | n/a"),
        "mean relative error must render 'n/a' for zero-evaluated frequency");
  }
}
