package org.hammer.audio.experimental.acoustic.wingbeat;

import java.util.Objects;

/**
 * Result of a wingbeat classification.
 *
 * <p>Every result carries a label string (one of the constants defined in {@link WingbeatLabel})
 * and a confidence value in {@code [0,1]}. An optional reference to the feature vector used for
 * classification is included for traceability and debugging.
 *
 * <p>Callers should not treat the {@link #confidence()} as a calibrated probability without
 * empirical validation against a labelled dataset. It is a heuristic score.
 *
 * @param label classification label; must not be blank
 * @param confidence classification confidence in {@code [0,1]}
 * @param featureVector the feature vector that produced this result, or {@code null} if not
 *     retained
 */
public record ClassificationResult(
    String label, double confidence, WingbeatFeatureVector featureVector) {

  /* Validate fields. */
  public ClassificationResult {
    Objects.requireNonNull(label, "label");
    if (label.isBlank()) {
      throw new IllegalArgumentException("label must not be blank");
    }
    if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
      throw new IllegalArgumentException("confidence must be finite and in [0,1]");
    }
  }

  /**
   * Create a result without retaining the feature vector.
   *
   * @param label classification label; must not be blank
   * @param confidence classification confidence in {@code [0,1]}
   */
  public ClassificationResult(String label, double confidence) {
    this(label, confidence, null);
  }

  /**
   * Create an unknown result with zero confidence and no feature vector.
   *
   * @return unknown classification result
   */
  public static ClassificationResult unknown() {
    return new ClassificationResult(WingbeatLabel.UNKNOWN, 0.0, null);
  }
}
