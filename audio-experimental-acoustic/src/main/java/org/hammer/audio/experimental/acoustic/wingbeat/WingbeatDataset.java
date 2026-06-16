package org.hammer.audio.experimental.acoustic.wingbeat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A labelled dataset of wingbeat recordings for classifier evaluation.
 *
 * <p>A {@code WingbeatDataset} holds a collection of {@link LabelledRecording} entries. The {@link
 * #evaluate(WingbeatClassifier)} method runs a classifier against every entry and returns an {@link
 * Evaluation} summary.
 *
 * <p>This abstraction allows different classifiers to be compared against identical reference data
 * without hard-coding any specific dataset format or filesystem layout.
 *
 * @param name human-readable dataset name; must not be blank
 * @param entries the labelled recordings in this dataset; must not be empty
 */
public record WingbeatDataset(String name, List<LabelledRecording> entries) {

  /** Validate and defensively copy the entry list. */
  public WingbeatDataset {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(entries, "entries");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("entries must not be empty");
    }
    entries = List.copyOf(entries);
  }

  /**
   * Evaluate a classifier against every entry in this dataset.
   *
   * <p>Each entry's feature vector is passed to the classifier; the predicted label is compared
   * against the ground-truth label. The result includes overall accuracy plus per-label sample and
   * correct-classification counts.
   *
   * @param classifier the classifier to evaluate; must not be {@code null}
   * @return evaluation summary; never {@code null}
   */
  public Evaluation evaluate(WingbeatClassifier classifier) {
    Objects.requireNonNull(classifier, "classifier");
    int correct = 0;
    Map<String, Integer> labelSampleCounts = new LinkedHashMap<>();
    Map<String, Integer> labelCorrectCounts = new LinkedHashMap<>();
    Map<String, Map<String, Integer>> confusionMatrix = new LinkedHashMap<>();
    for (LabelledRecording recording : entries) {
      String groundTruthLabel = recording.groundTruthLabel();
      labelSampleCounts.merge(groundTruthLabel, 1, Integer::sum);
      labelCorrectCounts.putIfAbsent(groundTruthLabel, 0);
      confusionMatrix.computeIfAbsent(groundTruthLabel, k -> new LinkedHashMap<>());
      ClassificationResult result = classifier.classify(recording.features());
      String predictedLabel = result.label();
      confusionMatrix.get(groundTruthLabel).merge(predictedLabel, 1, Integer::sum);
      if (groundTruthLabel.equals(predictedLabel)) {
        correct++;
        labelCorrectCounts.merge(groundTruthLabel, 1, Integer::sum);
      }
    }
    return new Evaluation(
        name, entries.size(), correct, labelSampleCounts, labelCorrectCounts, confusionMatrix);
  }

  /**
   * Summary of a classifier evaluation against a {@link WingbeatDataset}.
   *
   * @param datasetName the name of the evaluated dataset
   * @param sampleCount total number of evaluated recordings
   * @param correctCount number of correctly classified recordings
   * @param labelSampleCounts total evaluated recordings per ground-truth label
   * @param labelCorrectCounts correctly classified recordings per ground-truth label
   * @param confusionMatrix full confusion matrix; outer key is ground-truth label, inner key is
   *     predicted label, value is count
   */
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  public record Evaluation(
      String datasetName,
      int sampleCount,
      int correctCount,
      Map<String, Integer> labelSampleCounts,
      Map<String, Integer> labelCorrectCounts,
      Map<String, Map<String, Integer>> confusionMatrix) {

    /* Validate counts. */
    public Evaluation {
      Objects.requireNonNull(datasetName, "datasetName");
      Objects.requireNonNull(labelSampleCounts, "labelSampleCounts");
      Objects.requireNonNull(labelCorrectCounts, "labelCorrectCounts");
      Objects.requireNonNull(confusionMatrix, "confusionMatrix");
      if (sampleCount < 0) {
        throw new IllegalArgumentException("sampleCount must be >= 0");
      }
      if (correctCount < 0 || correctCount > sampleCount) {
        throw new IllegalArgumentException("correctCount must be in [0, sampleCount]");
      }
      labelSampleCounts = Map.copyOf(labelSampleCounts);
      labelCorrectCounts = Map.copyOf(labelCorrectCounts);
      Map<String, Map<String, Integer>> confusionCopy = new LinkedHashMap<>();
      for (Map.Entry<String, Map<String, Integer>> row : confusionMatrix.entrySet()) {
        confusionCopy.put(row.getKey(), Map.copyOf(row.getValue()));
      }
      confusionMatrix = Map.copyOf(confusionCopy);
      int totalLabelSamples = 0;
      for (Map.Entry<String, Integer> entry : labelSampleCounts.entrySet()) {
        String label = Objects.requireNonNull(entry.getKey(), "labelSampleCounts key");
        Integer count = Objects.requireNonNull(entry.getValue(), "labelSampleCounts value");
        if (label.isBlank()) {
          throw new IllegalArgumentException("labelSampleCounts keys must not be blank");
        }
        if (count < 0) {
          throw new IllegalArgumentException("labelSampleCounts values must be >= 0");
        }
        totalLabelSamples += count;
      }
      int totalLabelCorrect = 0;
      for (Map.Entry<String, Integer> entry : labelCorrectCounts.entrySet()) {
        String label = Objects.requireNonNull(entry.getKey(), "labelCorrectCounts key");
        Integer count = Objects.requireNonNull(entry.getValue(), "labelCorrectCounts value");
        if (label.isBlank()) {
          throw new IllegalArgumentException("labelCorrectCounts keys must not be blank");
        }
        if (count < 0) {
          throw new IllegalArgumentException("labelCorrectCounts values must be >= 0");
        }
        Integer labelSamples = labelSampleCounts.get(label);
        if (labelSamples == null) {
          throw new IllegalArgumentException(
              "labelCorrectCounts labels must also exist in labelSampleCounts");
        }
        if (count > labelSamples) {
          throw new IllegalArgumentException(
              "labelCorrectCounts values must be <= corresponding labelSampleCounts values");
        }
        totalLabelCorrect += count;
      }
      if (totalLabelSamples != sampleCount) {
        throw new IllegalArgumentException("labelSampleCounts must sum to sampleCount");
      }
      if (totalLabelCorrect != correctCount) {
        throw new IllegalArgumentException("labelCorrectCounts must sum to correctCount");
      }
    }

    /**
     * Classification accuracy as a ratio in {@code [0,1]}, or {@code null} when the dataset is
     * empty.
     *
     * @return accuracy or {@code null}
     */
    public Double accuracy() {
      return sampleCount == 0 ? null : correctCount / (double) sampleCount;
    }

    /**
     * Precision for the given predicted label: {@code TP / (TP + FP)}, or {@code null} when no
     * samples were predicted as that label.
     *
     * <p>Precision answers: of everything the classifier predicted as {@code label}, how many were
     * actually that label?
     *
     * @param label the label to compute precision for
     * @return precision in {@code [0,1]}, or {@code null} when unpredictable
     */
    public Double precision(String label) {
      Objects.requireNonNull(label, "label");
      int truePositives = labelCorrectCounts.getOrDefault(label, 0);
      int totalPredicted = 0;
      for (Map<String, Integer> row : confusionMatrix.values()) {
        totalPredicted += row.getOrDefault(label, 0);
      }
      return totalPredicted == 0 ? null : truePositives / (double) totalPredicted;
    }

    /**
     * Recall for the given ground-truth label: {@code TP / (TP + FN)}, or {@code null} when no
     * samples exist for that label.
     *
     * <p>Recall answers: of all recordings that are actually {@code label}, how many did the
     * classifier correctly identify?
     *
     * @param label the label to compute recall for
     * @return recall in {@code [0,1]}, or {@code null} when no samples exist for that label
     */
    public Double recall(String label) {
      Objects.requireNonNull(label, "label");
      Integer total = labelSampleCounts.get(label);
      if (total == null || total == 0) {
        return null;
      }
      return labelCorrectCounts.getOrDefault(label, 0) / (double) total;
    }

    /**
     * Set of all labels that appear in the confusion matrix (either as actual or predicted).
     *
     * @return sorted set of all observed labels
     */
    public Set<String> allLabels() {
      Set<String> labels = new java.util.TreeSet<>(labelSampleCounts.keySet());
      for (Map<String, Integer> row : confusionMatrix.values()) {
        labels.addAll(row.keySet());
      }
      return labels;
    }
  }
}
