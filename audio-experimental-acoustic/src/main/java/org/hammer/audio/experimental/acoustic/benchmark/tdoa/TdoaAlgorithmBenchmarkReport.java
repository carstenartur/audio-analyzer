package org.hammer.audio.experimental.acoustic.benchmark.tdoa;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Immutable side-by-side TDOA benchmark report. */
public record TdoaAlgorithmBenchmarkReport(List<TdoaAlgorithmBenchmarkResult> results) {

  // Validate stable algorithm ordering and defensively copy results.
  public TdoaAlgorithmBenchmarkReport {
    results = List.copyOf(Objects.requireNonNull(results, "results"));
    if (results.isEmpty()) {
      throw new IllegalArgumentException("results must not be empty");
    }
  }

  /** Returns the lowest-mean-error result. */
  public TdoaAlgorithmBenchmarkResult mostAccurate() {
    return results.stream()
        .min(Comparator.comparingDouble(TdoaAlgorithmBenchmarkResult::meanAbsoluteErrorSamples))
        .orElseThrow();
  }

  /** Renders deterministic Markdown suitable for workbench or documentation output. */
  public String toMarkdown() {
    StringBuilder output =
        new StringBuilder(
            "# TDOA Algorithm Benchmark\n\n"
                + "| Algorithm | Cases | Mean abs. error (samples) | Max abs. error (samples) |"
                + " Mean confidence | Ambiguous |\n"
                + "|---|---:|---:|---:|---:|---:|\n");
    for (TdoaAlgorithmBenchmarkResult result : results) {
      output.append(
          String.format(
              Locale.ROOT,
              "| %s | %d | %.6f | %.6f | %.6f | %d |%n",
              result.algorithmName(),
              result.caseCount(),
              result.meanAbsoluteErrorSamples(),
              result.maximumAbsoluteErrorSamples(),
              result.meanConfidence(),
              result.ambiguousCount()));
    }
    return output.toString();
  }
}
