package org.hammer.audio.experimental.acoustic.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.hammer.audio.acquisition.SynchronizationAssessment;
import org.hammer.audio.experimental.acoustic.TdoaConsistencyFinding;
import org.hammer.audio.experimental.acoustic.TdoaConsistencyReport;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios;
import org.hammer.audio.experimental.acoustic.tracking.TrackingSnapshot;
import org.junit.jupiter.api.Test;

class WorkbenchTdoaConsistencyExportTest {

  @Test
  void exportsPairConsistencyEvidenceAcrossSupportedFormats() {
    TrackingSnapshot snapshot = snapshot();
    WorkbenchRunResult result =
        new WorkbenchRunResult(
            SimulationScenarios.singleSource(),
            WorkbenchParameters.defaults().build(),
            List.of(snapshot),
            10L,
            null);

    assertEquals(0.35, result.meanTdoaConsistencyScore(), 1.0e-12);
    assertEquals(40.0e-6, result.maximumTdoaCycleResidualSeconds(), 1.0e-12);
    assertEquals(1, result.physicalTdoaViolationCount());
    assertEquals(1L, result.unreliableTdoaFrameCount());

    String markdown = WorkbenchRunExporter.toMarkdown(result);
    assertTrue(markdown.contains("Mean TDOA consistency | 0.3500"));
    assertTrue(markdown.contains("Physical TDOA violations | 1"));
    assertTrue(markdown.contains("| 0.3500 | 40.00 | 1 |"));

    String csv = WorkbenchRunExporter.toCsv(result);
    assertTrue(csv.contains("tdoaConsistencyScore,tdoaMaximumCycleResidualSeconds"));
    assertTrue(csv.contains("0.350000,0.000040000,1"));

    String json = WorkbenchRunExporter.toJsonLines(result);
    assertTrue(
        json.contains(
            "\"tdoaConsistency\":{\"score\":0.350000,\"evaluatedCycles\":4,\"maximumCycleResidualSeconds\":0.000040000,\"physicalViolationCount\":1}"));
  }

  private static TrackingSnapshot snapshot() {
    TdoaConsistencyFinding finding =
        new TdoaConsistencyFinding(
            TdoaConsistencyFinding.Kind.PHYSICAL_LIMIT,
            List.of("m0", "m1"),
            20.0e-6,
            5.0e-6);
    TdoaConsistencyReport report =
        new TdoaConsistencyReport(List.of(finding), 4, 20.0e-6, 40.0e-6, 1, 0.35);
    return new TrackingSnapshot(
        0L,
        0L,
        List.of(),
        List.of(),
        10L,
        Map.of(),
        SynchronizationAssessment.nominalSharedClock(),
        report);
  }
}
