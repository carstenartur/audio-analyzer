package org.hammer.audio.experimental.acoustic.benchmark.classifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Result of running a {@link ClassifierBenchmark} on a labelled dataset.
 *
 * <p>Provides the full confusion matrix plus per-label precision, recall and F1, and macro-averaged
 * F1 across all labels.
 *
 * @param confusionMatrix the confusion matrix; must not be {@code null}
 * @param precisionPerLabel per-label precision ({@code null} entries indicate no predictions for
 *     that label)
 * @param recallPerLabel per-label recall ({@code null} entries indicate no samples for that label)
 * @param f1PerLabel per-label F1 ({@code null} entries indicate unavailable precision or recall)
 * @param macroF1 macro-average F1 across labels for which F1 is defined, or {@code 0} when no F1 is
 *     defined
 */
@SuppressWarnings("PMD.UseConcurrentHashMap")
public record ClassifierBenchmarkResult(
    ConfusionMatrix confusionMatrix,
    Map<String, Double> precisionPerLabel,
    Map<String, Double> recallPerLabel,
    Map<String, Double> f1PerLabel,
    double macroF1) {

  /** Validate and defensively copy fields. */
  public ClassifierBenchmarkResult {
    Objects.requireNonNull(confusionMatrix, "confusionMatrix");
    Objects.requireNonNull(precisionPerLabel, "precisionPerLabel");
    Objects.requireNonNull(recallPerLabel, "recallPerLabel");
    Objects.requireNonNull(f1PerLabel, "f1PerLabel");
    if (!Double.isFinite(macroF1) || macroF1 < 0.0 || macroF1 > 1.0) {
      throw new IllegalArgumentException("macroF1 must be finite and in [0,1]");
    }
    precisionPerLabel = Collections.unmodifiableMap(new LinkedHashMap<>(precisionPerLabel));
    recallPerLabel = Collections.unmodifiableMap(new LinkedHashMap<>(recallPerLabel));
    f1PerLabel = Collections.unmodifiableMap(new LinkedHashMap<>(f1PerLabel));
  }

  /**
   * Build a result from a confusion matrix by computing all metrics.
   *
   * @param matrix populated confusion matrix; must not be {@code null}
   * @return result record; never {@code null}
   */
  public static ClassifierBenchmarkResult of(ConfusionMatrix matrix) {
    Objects.requireNonNull(matrix, "matrix");
    Set<String> labels = matrix.labels();
    Map<String, Double> precision = new LinkedHashMap<>();
    Map<String, Double> recall = new LinkedHashMap<>();
    Map<String, Double> f1 = new LinkedHashMap<>();
    double f1Sum = 0.0;
    int f1Count = 0;
    for (String label : labels) {
      Double p = matrix.precision(label);
      Double r = matrix.recall(label);
      Double f = matrix.f1(label);
      if (p != null) {
        precision.put(label, p);
      }
      if (r != null) {
        recall.put(label, r);
      }
      if (f != null) {
        f1.put(label, f);
        f1Sum += f;
        f1Count++;
      }
    }
    double macroF1 = f1Count == 0 ? 0.0 : f1Sum / f1Count;
    return new ClassifierBenchmarkResult(matrix, precision, recall, f1, macroF1);
  }
}
