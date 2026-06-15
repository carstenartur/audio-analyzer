package org.hammer.audio.experimental.acoustic.dataset;

import java.util.Map;
import java.util.Objects;

/**
 * Annotation attached to one recording.
 *
 * @param startSeconds annotation start time in seconds (inclusive)
 * @param endSeconds annotation end time in seconds (inclusive)
 * @param label annotation label (for example "mosquito_event")
 * @param metadata annotation metadata values; never {@code null}
 */
public record DatasetAnnotation(
    double startSeconds, double endSeconds, String label, Map<String, String> metadata) {

  public DatasetAnnotation {
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(metadata, "metadata");
    if (!Double.isFinite(startSeconds) || startSeconds < 0.0) {
      throw new IllegalArgumentException("startSeconds must be finite and >= 0");
    }
    if (!Double.isFinite(endSeconds) || endSeconds < startSeconds) {
      throw new IllegalArgumentException("endSeconds must be finite and >= startSeconds");
    }
    if (label.isBlank()) {
      throw new IllegalArgumentException("label must not be blank");
    }
    metadata = validateAndCopyMap(metadata, "metadata");
  }

  private static Map<String, String> validateAndCopyMap(Map<String, String> values, String field) {
    for (Map.Entry<String, String> entry : values.entrySet()) {
      String key = Objects.requireNonNull(entry.getKey(), field + " key");
      String value = Objects.requireNonNull(entry.getValue(), field + " value");
      if (key.isBlank()) {
        throw new IllegalArgumentException(field + " keys must not be blank");
      }
      if (value.isBlank()) {
        throw new IllegalArgumentException(field + " values must not be blank");
      }
    }
    return Map.copyOf(values);
  }
}
