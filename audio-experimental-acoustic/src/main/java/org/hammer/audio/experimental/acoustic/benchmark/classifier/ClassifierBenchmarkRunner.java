package org.hammer.audio.experimental.acoustic.benchmark.classifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.experimental.acoustic.wingbeat.WingbeatFeatureVector;

/**
 * Runs multiple {@link ClassifierBenchmark} implementations against the same labelled dataset and
 * collects their results.
 *
 * <p>Adding a new classifier to the comparison requires only implementing {@link
 * ClassifierBenchmark} and including it in the list passed to this runner.
 *
 * <p>This service is stateless and may be called concurrently as long as the registered benchmarks
 * are also stateless.
 */
@SuppressWarnings("PMD.UseConcurrentHashMap")
public final class ClassifierBenchmarkRunner {

  private final List<ClassifierBenchmark> benchmarks;

  /**
   * Create a runner with the given benchmarks.
   *
   * @param benchmarks benchmarks to run; must not be {@code null} or empty
   */
  public ClassifierBenchmarkRunner(List<ClassifierBenchmark> benchmarks) {
    Objects.requireNonNull(benchmarks, "benchmarks");
    if (benchmarks.isEmpty()) {
      throw new IllegalArgumentException("benchmarks must not be empty");
    }
    this.benchmarks = List.copyOf(benchmarks);
  }

  /**
   * Create a runner containing the {@link RuleBasedClassifierBenchmark} as the sole default
   * benchmark.
   *
   * @return default runner instance; never {@code null}
   */
  public static ClassifierBenchmarkRunner defaultRunner() {
    return new ClassifierBenchmarkRunner(List.of(new RuleBasedClassifierBenchmark()));
  }

  /**
   * Run all registered benchmarks on the given labelled dataset.
   *
   * @param vectors feature vectors; must not be {@code null} or empty
   * @param labels ground-truth labels, one per vector; must not be {@code null}; must have the same
   *     size as {@code vectors}
   * @return map of benchmark name to result; order mirrors the registration order; never {@code
   *     null}
   */
  public Map<String, ClassifierBenchmarkResult> run(
      List<WingbeatFeatureVector> vectors, List<String> labels) {
    Objects.requireNonNull(vectors, "vectors");
    Objects.requireNonNull(labels, "labels");
    if (vectors.isEmpty()) {
      throw new IllegalArgumentException("vectors must not be empty");
    }
    if (vectors.size() != labels.size()) {
      throw new IllegalArgumentException("vectors and labels must have the same size");
    }

    Map<String, ClassifierBenchmarkResult> results = new LinkedHashMap<>();
    for (ClassifierBenchmark benchmark : benchmarks) {
      results.put(benchmark.name(), benchmark.run(vectors, labels));
    }
    return Collections.unmodifiableMap(results);
  }
}
