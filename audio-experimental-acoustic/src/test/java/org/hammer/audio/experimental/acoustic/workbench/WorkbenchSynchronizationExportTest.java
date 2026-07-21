package org.hammer.audio.experimental.acoustic.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.hammer.audio.acquisition.SynchronizationAssessment;
import org.hammer.audio.acquisition.SynchronizationMode;
import org.hammer.audio.acquisition.SynchronizationStatus;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios;
import org.hammer.audio.experimental.acoustic.tracking.TrackedSource;
import org.hammer.audio.experimental.acoustic.tracking.TrackingSnapshot;
import org.hammer.audio.geometry.Vector2;
import org.hammer.audio.geometry.Vector3;
import org.junit.jupiter.api.Test;

class WorkbenchSynchronizationExportTest {

  @Test
  void exportsAndDisplaysSynchronizationEvidence() {
    TrackingSnapshot snapshot = snapshot();
    WorkbenchRunResult result =
        new WorkbenchRunResult(
            SimulationScenarios.singleSource(),
            WorkbenchParameters.defaults().build(),
            List.of(snapshot),
            10L,
            null);

    assertEquals(SynchronizationStatus.DEGRADED, result.worstSynchronizationStatus());
    assertTrue(result.hasSynchronizationWarning());
    assertEquals(
        List.of(SynchronizationMode.CALIBRATED_OFFSET),
        result.synchronizationModes().stream().sorted().toList());

    String markdown = WorkbenchRunExporter.toMarkdown(result);
    assertTrue(markdown.contains("Synchronization modes | CALIBRATED_OFFSET"));
    assertTrue(markdown.contains("Worst synchronization | DEGRADED"));
    assertTrue(markdown.contains("| DEGRADED | 0.3500 |"));

    String csv = WorkbenchRunExporter.toCsv(result);
    assertTrue(csv.contains("synchronizationMode,synchronizationStatus"));
    assertTrue(csv.contains("CALIBRATED_OFFSET,DEGRADED,0.350000,false"));

    String json = WorkbenchRunExporter.toJsonLines(result);
    assertTrue(
        json.contains(
            "\"synchronization\":{\"mode\":\"CALIBRATED_OFFSET\",\"status\":\"DEGRADED\",\"estimatedErrorSamples\":0.350000,\"calibrationCurrent\":false}"));

    String playback = AcousticLocalizationWorkbenchPanel.formatSynchronizationDetails(snapshot);
    assertTrue(playback.contains("CALIBRATED_OFFSET / DEGRADED"));
    assertTrue(playback.contains("0.3500 samples"));
  }

  private static TrackingSnapshot snapshot() {
    SynchronizationAssessment synchronization =
        new SynchronizationAssessment(
            SynchronizationMode.CALIBRATED_OFFSET,
            SynchronizationStatus.DEGRADED,
            0.35,
            7.291666666666667e-6,
            false,
            List.of("Calibration consumes more than half of the error budget."));
    TrackedSource track =
        new TrackedSource(
            7, 512.0, 512.0, Vector2.ZERO, Vector2.ZERO, Vector3.ZERO, 0.0, 1.0, 0.8, 0L, 1);
    return new TrackingSnapshot(0L, 0L, List.of(), List.of(track), 10L, Map.of(), synchronization);
  }
}
