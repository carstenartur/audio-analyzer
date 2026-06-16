package org.hammer.audio.experimental.acoustic.benchmark.localization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios.SimulationScenario;
import org.junit.jupiter.api.Test;

class LocalizationBenchmarkRunnerTest {

  @Test
  void runnerProducesResultForEachBenchmark() {
    LocalizationBenchmarkRunner runner = LocalizationBenchmarkRunner.defaultRunner();
    List<SimulationScenario> scenarios = List.of(SimulationScenarios.singleSource());

    Map<String, List<LocalizationBenchmarkResult>> results = runner.run(scenarios);

    assertNotNull(results);
    assertEquals(1, results.size());
    assertTrue(results.containsKey("TdoaLocalizationBenchmark"));
  }

  @Test
  void singleSourceScenarioProducesFiniteLocalizationError() {
    TdoaLocalizationBenchmark benchmark = new TdoaLocalizationBenchmark();
    SimulationScenario scenario = SimulationScenarios.singleSource();

    LocalizationBenchmarkResult result = benchmark.run(scenario);

    assertNotNull(result);
    assertEquals("single-source", result.scenarioId());
    assertTrue(Double.isFinite(result.meanLocalizationErrorMeters()));
    assertTrue(result.meanLocalizationErrorMeters() >= 0.0);
  }

  @Test
  void trackingErrorIsInValidRange() {
    TdoaLocalizationBenchmark benchmark = new TdoaLocalizationBenchmark();
    SimulationScenario scenario = SimulationScenarios.singleSource();

    LocalizationBenchmarkResult result = benchmark.run(scenario);

    assertTrue(result.trackingError() >= 0.0 && result.trackingError() <= 1.0);
  }

  @Test
  void falsePositiveAndNegativeCountsAreNonNegative() {
    TdoaLocalizationBenchmark benchmark = new TdoaLocalizationBenchmark();
    SimulationScenario scenario = SimulationScenarios.singleSource();

    LocalizationBenchmarkResult result = benchmark.run(scenario);

    assertTrue(result.falsePositiveCount() >= 0);
    assertTrue(result.falseNegativeCount() >= 0);
  }

  @Test
  void runnerReturnsResultPerScenario() {
    LocalizationBenchmarkRunner runner = LocalizationBenchmarkRunner.defaultRunner();
    List<SimulationScenario> scenarios =
        List.of(SimulationScenarios.singleSource(), SimulationScenarios.noisyRoom());

    Map<String, List<LocalizationBenchmarkResult>> results = runner.run(scenarios);

    List<LocalizationBenchmarkResult> tdoaResults = results.get("TdoaLocalizationBenchmark");
    assertNotNull(tdoaResults);
    assertEquals(2, tdoaResults.size());
    assertFalse(tdoaResults.get(0).scenarioId().equals(tdoaResults.get(1).scenarioId()));
  }

  @Test
  void customBenchmarkCanBeRegistered() {
    // A trivial benchmark that always returns zero error
    LocalizationBenchmark trivial =
        scenario -> new LocalizationBenchmarkResult(scenario.name(), 0.0, 0.0, 0, 0, 0);
    LocalizationBenchmarkRunner runner = new LocalizationBenchmarkRunner(List.of(trivial));

    Map<String, List<LocalizationBenchmarkResult>> results =
        runner.run(List.of(SimulationScenarios.singleSource()));

    assertFalse(results.isEmpty());
    List<LocalizationBenchmarkResult> list = results.values().iterator().next();
    assertEquals(0.0, list.get(0).meanLocalizationErrorMeters());
  }

  @Test
  void constructorRejectsTooSmallBlockSize() {
    assertThrows(IllegalArgumentException.class, () -> new TdoaLocalizationBenchmark(127));
  }
}
