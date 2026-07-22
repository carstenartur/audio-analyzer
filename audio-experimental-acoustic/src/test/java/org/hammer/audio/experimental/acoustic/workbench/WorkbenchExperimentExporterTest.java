package org.hammer.audio.experimental.acoustic.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hammer.audio.acquisition.LocalizationExperimentStage;
import org.hammer.audio.acquisition.LocalizationInputMode;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios;
import org.junit.jupiter.api.Test;

class WorkbenchExperimentExporterTest {

  @Test
  void simulationRunCarriesAndExportsReproducibilityMetadata() {
    WorkbenchRunResult result =
        WorkbenchScenarioRunner.run(
            SimulationScenarios.singleSource(),
            WorkbenchParameters.defaults().blockSize(512).build());

    var experiment = result.experimentMetadata().orElseThrow();
    String manifest = WorkbenchExperimentExporter.toManifest(result);
    String markdown = WorkbenchExperimentExporter.toMarkdown(result);

    assertEquals(LocalizationInputMode.SIMULATION, experiment.inputMode());
    assertEquals(LocalizationExperimentStage.LOCALIZED, experiment.stage());
    assertEquals("simulation.single-source", experiment.profile().profileId());
    assertEquals(manifest, WorkbenchExperimentExporter.toManifest(result));
    assertTrue(manifest.contains("experiment.mode=SIMULATION"));
    assertTrue(markdown.contains("| Profile | simulation.single-source |"));
    assertTrue(markdown.contains("| Metadata: randomSeed | 1 |"));
  }
}
