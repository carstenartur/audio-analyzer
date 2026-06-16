package org.hammer.audio.experimental.acoustic.benchmark.localization;

import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios.SimulationScenario;

/**
 * Contract for localization benchmark implementations.
 *
 * <p>Implementations run a simulation scenario end-to-end and return a {@link
 * LocalizationBenchmarkResult}. Adding a new localization algorithm requires only implementing this
 * interface and registering it with a {@link LocalizationBenchmarkRunner}.
 */
@FunctionalInterface
public interface LocalizationBenchmark {

  /**
   * Run the benchmark on the given simulation scenario.
   *
   * @param scenario the scenario to run; must not be {@code null}
   * @return benchmark result; never {@code null}
   */
  LocalizationBenchmarkResult run(SimulationScenario scenario);

  /**
   * Human-readable benchmark name used as a key in {@link LocalizationBenchmarkRunner} output.
   *
   * <p>Defaults to the simple class name.
   *
   * @return benchmark name; never blank
   */
  default String name() {
    return getClass().getSimpleName();
  }
}
