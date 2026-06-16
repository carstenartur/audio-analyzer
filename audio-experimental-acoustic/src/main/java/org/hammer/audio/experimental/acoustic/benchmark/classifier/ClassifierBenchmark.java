package org.hammer.audio.experimental.acoustic.benchmark.classifier;

import java.util.List;
import org.hammer.audio.experimental.acoustic.wingbeat.WingbeatFeatureVector;

/**
 * Contract for classifier benchmarks.
 *
 * <p>Implementations receive a labelled collection of {@link WingbeatFeatureVector}s and return a
 * {@link ClassifierBenchmarkResult} containing precision, recall, F1 and a confusion matrix.
 *
 * <p>Adding a new classifier to the benchmark framework requires only implementing this interface
 * and registering the implementation with a {@link ClassifierBenchmarkRunner}.
 */
@FunctionalInterface
public interface ClassifierBenchmark {

  /**
   * Run the benchmark over the given labelled dataset.
   *
   * @param vectors feature vectors; must not be {@code null} or empty
   * @param labels ground-truth labels, one per vector; must not be {@code null}; must have the same
   *     size as {@code vectors}
   * @return benchmark result; never {@code null}
   */
  ClassifierBenchmarkResult run(List<WingbeatFeatureVector> vectors, List<String> labels);

  /**
   * Human-readable benchmark name used as a key in {@link ClassifierBenchmarkRunner} output.
   *
   * <p>Defaults to the simple class name.
   *
   * @return benchmark name; never blank
   */
  default String name() {
    return getClass().getSimpleName();
  }
}
