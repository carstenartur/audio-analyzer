package org.hammer.audio.experimental.acoustic.workbench;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hammer.audio.experimental.acoustic.GccPhatTdoaEstimator;
import org.hammer.audio.experimental.acoustic.SubSampleGccPhatTdoaEstimator;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios;
import org.junit.jupiter.api.Test;

class WorkbenchTdoaEstimatorSelectionTest {

  @Test
  void keepsIntegerBaselineAsDefaultAndExposesSubSampleStrategy() {
    WorkbenchParameters defaults = WorkbenchParameters.defaults().build();
    assertInstanceOf(
        GccPhatTdoaEstimator.class, WorkbenchScenarioRunner.buildTdoaEstimator(defaults));

    WorkbenchParameters advanced =
        WorkbenchParameters.defaults()
            .tdoaEstimatorType(WorkbenchParameters.TdoaEstimatorType.SUB_SAMPLE_GCC_PHAT)
            .build();
    assertInstanceOf(
        SubSampleGccPhatTdoaEstimator.class,
        WorkbenchScenarioRunner.buildTdoaEstimator(advanced));
  }

  @Test
  void rejectsMissingTdoaEstimatorStrategyAtConfigurationBoundary() {
    assertThrows(
        NullPointerException.class,
        () -> WorkbenchParameters.defaults().tdoaEstimatorType(null));
  }

  @Test
  void runsDeterministicScenarioWithSubSampleEstimator() {
    WorkbenchParameters parameters =
        WorkbenchParameters.defaults()
            .blockSize(512)
            .tdoaEstimatorType(WorkbenchParameters.TdoaEstimatorType.SUB_SAMPLE_GCC_PHAT)
            .build();

    WorkbenchRunResult result =
        WorkbenchScenarioRunner.run(SimulationScenarios.singleSource(), parameters);

    assertTrue(result.blockCount() > 0);
    assertTrue(result.totalProcessingNanos() > 0L);
  }
}
