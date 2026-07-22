package org.hammer.audio.experimental.acoustic.benchmark.tdoa;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.hammer.audio.experimental.acoustic.DiagnosticTdoaEstimate;
import org.hammer.audio.experimental.acoustic.DiagnosticTdoaEstimator;
import org.hammer.audio.experimental.acoustic.TdoaEstimate;

/** Runs named TDOA estimators over identical deterministic known-delay cases. */
public final class TdoaAlgorithmBenchmarkRunner {

  private final List<NamedTdoaEstimator> estimators;

  /** Creates a runner with unique non-empty estimator registrations. */
  public TdoaAlgorithmBenchmarkRunner(List<NamedTdoaEstimator> estimators) {
    this.estimators = List.copyOf(Objects.requireNonNull(estimators, "estimators"));
    if (this.estimators.isEmpty()) {
      throw new IllegalArgumentException("estimators must not be empty");
    }
    Set<String> names = new HashSet<>();
    for (NamedTdoaEstimator estimator : this.estimators) {
      if (!names.add(estimator.name())) {
        throw new IllegalArgumentException("duplicate estimator name: " + estimator.name());
      }
    }
  }

  /** Evaluates every estimator over the same ordered benchmark cases. */
  public TdoaAlgorithmBenchmarkReport run(List<TdoaBenchmarkCase> cases) {
    List<TdoaBenchmarkCase> requiredCases = List.copyOf(Objects.requireNonNull(cases, "cases"));
    if (requiredCases.isEmpty()) {
      throw new IllegalArgumentException("cases must not be empty");
    }
    List<TdoaAlgorithmBenchmarkResult> results = new ArrayList<>(estimators.size());
    for (NamedTdoaEstimator named : estimators) {
      results.add(runEstimator(named, requiredCases));
    }
    return new TdoaAlgorithmBenchmarkReport(results);
  }

  private static TdoaAlgorithmBenchmarkResult runEstimator(
      NamedTdoaEstimator named, List<TdoaBenchmarkCase> cases) {
    double totalAbsoluteError = 0.0;
    double maximumAbsoluteError = 0.0;
    double totalConfidence = 0.0;
    int ambiguousCount = 0;
    for (TdoaBenchmarkCase benchmarkCase : cases) {
      TdoaEstimate estimate;
      if (named.estimator() instanceof DiagnosticTdoaEstimator diagnostic) {
        DiagnosticTdoaEstimate detailed =
            diagnostic.estimateDetailed(
                benchmarkCase.block(),
                benchmarkCase.array(),
                benchmarkCase.firstChannel(),
                benchmarkCase.secondChannel());
        estimate = detailed.estimate();
        if (detailed.diagnostics().ambiguous()) {
          ambiguousCount++;
        }
      } else {
        estimate =
            named
                .estimator()
                .estimate(
                    benchmarkCase.block(),
                    benchmarkCase.array(),
                    benchmarkCase.firstChannel(),
                    benchmarkCase.secondChannel());
      }
      double estimatedDelaySamples =
          estimate.delaySeconds() * benchmarkCase.block().format().sampleRate();
      double absoluteError = Math.abs(estimatedDelaySamples - benchmarkCase.expectedDelaySamples());
      totalAbsoluteError += absoluteError;
      maximumAbsoluteError = Math.max(maximumAbsoluteError, absoluteError);
      totalConfidence += estimate.confidence();
    }
    return new TdoaAlgorithmBenchmarkResult(
        named.name(),
        cases.size(),
        totalAbsoluteError / cases.size(),
        maximumAbsoluteError,
        totalConfidence / cases.size(),
        ambiguousCount);
  }
}
