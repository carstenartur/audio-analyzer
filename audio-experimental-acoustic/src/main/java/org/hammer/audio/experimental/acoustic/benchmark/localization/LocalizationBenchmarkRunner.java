package org.hammer.audio.experimental.acoustic.benchmark.localization;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios.SimulationScenario;

/**
 * Runs multiple {@link LocalizationBenchmark} implementations across a set of simulation scenarios
 * and aggregates the results.
 *
 * <p>Adding a new localization algorithm requires only implementing {@link LocalizationBenchmark}
 * and including it in the list passed to this runner.
 *
 * <p>This service is stateless and may be called concurrently as long as the registered benchmarks
 * are also stateless.
 */
@SuppressWarnings("PMD.UseConcurrentHashMap")
public final class LocalizationBenchmarkRunner {

  private final List<LocalizationBenchmark> benchmarks;

  /**
   * Create a runner with the given benchmarks.
   *
   * @param benchmarks benchmarks to run; must not be {@code null} or empty
   */
  public LocalizationBenchmarkRunner(List<LocalizationBenchmark> benchmarks) {
    Objects.requireNonNull(benchmarks, "benchmarks");
    if (benchmarks.isEmpty()) {
      throw new IllegalArgumentException("benchmarks must not be empty");
    }
    this.benchmarks = List.copyOf(benchmarks);
  }

  /**
   * Create a runner containing {@link TdoaLocalizationBenchmark} as the sole default benchmark.
   *
   * @return default runner instance; never {@code null}
   */
  public static LocalizationBenchmarkRunner defaultRunner() {
    return new LocalizationBenchmarkRunner(List.of(new TdoaLocalizationBenchmark()));
  }

  /**
   * Run all registered benchmarks on each scenario in the given list.
   *
   * <p>The outer map key is the benchmark name; the inner list contains one result per scenario in
   * the order supplied.
   *
   * @param scenarios the scenarios to benchmark; must not be {@code null} or empty
   * @return map of benchmark name to per-scenario results; never {@code null}
   */
  public Map<String, List<LocalizationBenchmarkResult>> run(List<SimulationScenario> scenarios) {
    Objects.requireNonNull(scenarios, "scenarios");
    if (scenarios.isEmpty()) {
      throw new IllegalArgumentException("scenarios must not be empty");
    }

    Map<String, List<LocalizationBenchmarkResult>> results = new LinkedHashMap<>();
    for (LocalizationBenchmark benchmark : benchmarks) {
      List<LocalizationBenchmarkResult> benchmarkResults =
          new java.util.ArrayList<>(scenarios.size());
      for (SimulationScenario scenario : scenarios) {
        benchmarkResults.add(benchmark.run(scenario));
      }
      results.put(benchmark.name(), Collections.unmodifiableList(benchmarkResults));
    }
    return Collections.unmodifiableMap(results);
  }
}
