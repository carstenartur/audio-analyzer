package org.hammer.audio.experimental.acoustic.wingbeat;

import java.util.Objects;

/**
 * An entry in a labelled recording dataset for classifier evaluation.
 *
 * <p>A {@code LabelledRecording} pairs a pre-extracted {@link WingbeatFeatureVector} with a known
 * ground-truth label. This allows classifiers to be evaluated against reference datasets without
 * coupling the evaluation framework to a specific audio format or file layout.
 *
 * <p>The ground-truth label must be one of the constants in {@link WingbeatLabel} or a custom
 * domain-specific string. An empty or blank label is not permitted.
 *
 * @param id unique identifier for this recording within the dataset; must not be blank
 * @param description human-readable description of the recording (species, sex, conditions, etc.)
 * @param groundTruthLabel the known classification label; must not be blank
 * @param features the pre-extracted feature vector for this recording; must not be {@code null}
 */
public record LabelledRecording(
    String id, String description, String groundTruthLabel, WingbeatFeatureVector features) {

  /* Validate fields. */
  public LabelledRecording {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(groundTruthLabel, "groundTruthLabel");
    Objects.requireNonNull(features, "features");
    if (id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    if (groundTruthLabel.isBlank()) {
      throw new IllegalArgumentException("groundTruthLabel must not be blank");
    }
  }
}
