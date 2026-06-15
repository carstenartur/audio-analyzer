package org.hammer.audio.experimental.acoustic.dataset;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One normalized recording entry in a dataset manifest.
 *
 * @param recordingId unique recording identifier inside one dataset
 * @param audioPath path to audio file (relative to {@link DatasetDescriptor#localRootPath()} or
 *     absolute)
 * @param sampleRateHz audio sampling rate in hertz
 * @param durationSeconds clip duration in seconds
 * @param labels semantic labels (species, sex, age, feeding status, etc.)
 * @param annotations optional event/time annotations
 * @param metadata additional metadata key/value entries
 */
public record DatasetRecording(
    String recordingId,
    Path audioPath,
    double sampleRateHz,
    double durationSeconds,
    Map<String, String> labels,
    List<DatasetAnnotation> annotations,
    Map<String, String> metadata) {

  public DatasetRecording {
    Objects.requireNonNull(recordingId, "recordingId");
    Objects.requireNonNull(audioPath, "audioPath");
    Objects.requireNonNull(labels, "labels");
    Objects.requireNonNull(annotations, "annotations");
    Objects.requireNonNull(metadata, "metadata");
    if (recordingId.isBlank()) {
      throw new IllegalArgumentException("recordingId must not be blank");
    }
    if (!Double.isFinite(sampleRateHz) || sampleRateHz <= 0.0) {
      throw new IllegalArgumentException("sampleRateHz must be finite and > 0");
    }
    if (!Double.isFinite(durationSeconds) || durationSeconds < 0.0) {
      throw new IllegalArgumentException("durationSeconds must be finite and >= 0");
    }
    labels = validateAndCopyMap(labels, "labels");
    metadata = validateAndCopyMap(metadata, "metadata");
    annotations = List.copyOf(annotations);
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
