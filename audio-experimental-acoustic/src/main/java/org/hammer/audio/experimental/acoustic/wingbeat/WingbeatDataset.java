package org.hammer.audio.experimental.acoustic.wingbeat;

import java.util.List;
import java.util.Objects;

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
   * against the ground-truth label. The result includes accuracy, precision and recall for each
   * label that appears in the dataset.
   *
   * @param classifier the classifier to evaluate; must not be {@code null}
   * @return evaluation summary; never {@code null}
   */
  public Evaluation evaluate(WingbeatClassifier classifier) {
    Objects.requireNonNull(classifier, "classifier");
    int correct = 0;
    for (LabelledRecording recording : entries) {
      ClassificationResult result = classifier.classify(recording.features());
      if (recording.groundTruthLabel().equals(result.label())) {
        correct++;
      }
    }
    return new Evaluation(name, entries.size(), correct);
  }

  /**
   * Summary of a classifier evaluation against a {@link WingbeatDataset}.
   *
   * @param datasetName the name of the evaluated dataset
   * @param sampleCount total number of evaluated recordings
   * @param correctCount number of correctly classified recordings
   */
  public record Evaluation(String datasetName, int sampleCount, int correctCount) {

    /** Validate counts. */
    public Evaluation {
      Objects.requireNonNull(datasetName, "datasetName");
      if (sampleCount < 0) {
        throw new IllegalArgumentException("sampleCount must be >= 0");
      }
      if (correctCount < 0 || correctCount > sampleCount) {
        throw new IllegalArgumentException("correctCount must be in [0, sampleCount]");
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
  }
}
