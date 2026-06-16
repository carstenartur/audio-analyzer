package org.hammer.audio.experimental.acoustic.benchmark.classifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Label-indexed confusion matrix.
 *
 * <p>Rows correspond to actual (ground-truth) labels; columns correspond to predicted labels. All
 * per-label metrics — accuracy, precision, recall and F1 — are computed on demand from the matrix.
 */
@SuppressWarnings("PMD.UseConcurrentHashMap")
public final class ConfusionMatrix {

  private final Map<String, Map<String, Integer>> matrix;
  private final int totalCount;

  private ConfusionMatrix(Map<String, Map<String, Integer>> matrix, int totalCount) {
    this.matrix = matrix;
    this.totalCount = totalCount;
  }

  /**
   * Build a confusion matrix from parallel lists of actual and predicted labels.
   *
   * @param actual actual (ground-truth) labels; must not be {@code null}
   * @param predicted predicted labels; must not be {@code null}; must have the same size as {@code
   *     actual}
   * @return confusion matrix; never {@code null}
   */
  public static ConfusionMatrix of(List<String> actual, List<String> predicted) {
    Objects.requireNonNull(actual, "actual");
    Objects.requireNonNull(predicted, "predicted");
    if (actual.size() != predicted.size()) {
      throw new IllegalArgumentException("actual and predicted must have the same size");
    }
    Map<String, Map<String, Integer>> m = new LinkedHashMap<>();
    for (int i = 0; i < actual.size(); i++) {
      String act = Objects.requireNonNull(actual.get(i), "actual label must not be null");
      String pred = Objects.requireNonNull(predicted.get(i), "predicted label must not be null");
      m.computeIfAbsent(act, k -> new LinkedHashMap<>()).merge(pred, 1, Integer::sum);
    }
    // Unmodifiable
    Map<String, Map<String, Integer>> immutable = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, Integer>> entry : m.entrySet()) {
      immutable.put(
          entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
    }
    return new ConfusionMatrix(Collections.unmodifiableMap(immutable), actual.size());
  }

  /**
   * Total number of samples in the confusion matrix.
   *
   * @return total count; always {@code >= 0}
   */
  public int totalCount() {
    return totalCount;
  }

  /**
   * Overall accuracy: fraction of correctly classified samples.
   *
   * @return accuracy in {@code [0,1]}, or {@code 0} when the matrix is empty
   */
  public double accuracy() {
    if (totalCount == 0) {
      return 0.0;
    }
    int correct = 0;
    for (Map.Entry<String, Map<String, Integer>> row : matrix.entrySet()) {
      correct += row.getValue().getOrDefault(row.getKey(), 0);
    }
    return correct / (double) totalCount;
  }

  /**
   * Precision for the given predicted label: {@code TP / (TP + FP)}, or {@code null} when no
   * samples were predicted as that label.
   *
   * @param label target label; must not be {@code null}
   * @return precision in {@code [0,1]}, or {@code null}
   */
  public Double precision(String label) {
    Objects.requireNonNull(label, "label");
    int truePositives = 0;
    int totalPredicted = 0;
    for (Map.Entry<String, Map<String, Integer>> row : matrix.entrySet()) {
      int cellCount = row.getValue().getOrDefault(label, 0);
      totalPredicted += cellCount;
      if (label.equals(row.getKey())) {
        truePositives = cellCount;
      }
    }
    return totalPredicted == 0 ? null : truePositives / (double) totalPredicted;
  }

  /**
   * Recall for the given actual label: {@code TP / (TP + FN)}, or {@code null} when no samples
   * exist for that label.
   *
   * @param label target label; must not be {@code null}
   * @return recall in {@code [0,1]}, or {@code null}
   */
  public Double recall(String label) {
    Objects.requireNonNull(label, "label");
    Map<String, Integer> row = matrix.get(label);
    if (row == null) {
      return null;
    }
    int total = 0;
    for (int c : row.values()) {
      total += c;
    }
    if (total == 0) {
      return null;
    }
    return row.getOrDefault(label, 0) / (double) total;
  }

  /**
   * F1 score for the given label: harmonic mean of precision and recall, or {@code null} when
   * either metric is unavailable.
   *
   * @param label target label; must not be {@code null}
   * @return F1 score in {@code [0,1]}, or {@code null}
   */
  public Double f1(String label) {
    Objects.requireNonNull(label, "label");
    Double p = precision(label);
    Double r = recall(label);
    if (p == null || r == null || (p + r) == 0.0) {
      return null;
    }
    return 2.0 * p * r / (p + r);
  }

  /**
   * Count the number of samples where the actual label is {@code actual} and the predicted label is
   * {@code predicted}.
   *
   * @param actual actual label; must not be {@code null}
   * @param predicted predicted label; must not be {@code null}
   * @return count; always {@code >= 0}
   */
  public int count(String actual, String predicted) {
    Objects.requireNonNull(actual, "actual");
    Objects.requireNonNull(predicted, "predicted");
    Map<String, Integer> row = matrix.get(actual);
    return row == null ? 0 : row.getOrDefault(predicted, 0);
  }

  /**
   * Set of all labels observed as actual labels in the matrix.
   *
   * @return label set; never {@code null}
   */
  public Set<String> labels() {
    return new LinkedHashSet<>(matrix.keySet());
  }

  /**
   * Return an unmodifiable view of the underlying matrix (actual → predicted → count).
   *
   * @return confusion matrix map; never {@code null}
   */
  public Map<String, Map<String, Integer>> asMap() {
    return matrix;
  }

  /**
   * Render a compact Markdown table for this confusion matrix.
   *
   * @return Markdown snippet; never {@code null}
   */
  public String toMarkdown() {
    List<String> labelList = new ArrayList<>(labels());
    if (labelList.isEmpty()) {
      return "*(empty confusion matrix)*\n";
    }
    StringBuilder sb = new StringBuilder(256);
    sb.append("| Actual \\ Predicted |");
    for (String l : labelList) {
      sb.append(' ').append(l).append(" |");
    }
    sb.append("\n|---|").append("---|".repeat(labelList.size())).append('\n');
    for (String act : labelList) {
      sb.append("| ").append(act).append(" |");
      for (String pred : labelList) {
        sb.append(' ').append(count(act, pred)).append(" |");
      }
      sb.append('\n');
    }
    return sb.toString();
  }
}
