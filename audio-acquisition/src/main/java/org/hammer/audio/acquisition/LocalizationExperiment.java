package org.hammer.audio.acquisition;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Reproducible experiment identity shared by simulation, replay and live localization.
 *
 * @param experimentId stable experiment identity
 * @param displayName human-readable experiment name
 * @param profile complete array, hardware and calibration profile
 * @param inputMode selected sample origin
 * @param sourceReference deterministic source, recording or live-session reference
 * @param createdAt experiment creation time
 * @param stage current lifecycle stage
 * @param metadata ordered additional experiment metadata
 */
public record LocalizationExperiment(
    String experimentId,
    String displayName,
    MicrophoneArrayProfile profile,
    LocalizationInputMode inputMode,
    String sourceReference,
    Instant createdAt,
    LocalizationExperimentStage stage,
    Map<String, String> metadata) {

  /** Validate and defensively copy one experiment. */
  public LocalizationExperiment {
    requireText(experimentId, "experimentId");
    requireText(displayName, "displayName");
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(inputMode, "inputMode");
    requireText(sourceReference, "sourceReference");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(stage, "stage");
    if (!profile.supports(inputMode)) {
      throw new IllegalArgumentException("profile does not support experiment input mode");
    }
    TreeMap<String, String> ordered = new TreeMap<>();
    for (Map.Entry<String, String> entry : Objects.requireNonNull(metadata, "metadata").entrySet()) {
      requireText(entry.getKey(), "metadata key");
      Objects.requireNonNull(entry.getValue(), "metadata value");
      ordered.put(entry.getKey(), entry.getValue());
    }
    metadata = Collections.unmodifiableMap(ordered);
  }

  /** Create a newly defined experiment. */
  public static LocalizationExperiment defined(
      String experimentId,
      String displayName,
      MicrophoneArrayProfile profile,
      LocalizationInputMode inputMode,
      String sourceReference,
      Instant createdAt) {
    return new LocalizationExperiment(
        experimentId,
        displayName,
        profile,
        inputMode,
        sourceReference,
        createdAt,
        LocalizationExperimentStage.DEFINED,
        Map.of());
  }

  /** Advance exactly one lifecycle stage. */
  public LocalizationExperiment advance() {
    LocalizationExperimentStage[] stages = LocalizationExperimentStage.values();
    if (stage.ordinal() + 1 >= stages.length) {
      return this;
    }
    return withStage(stages[stage.ordinal() + 1]);
  }

  /** Advance to the next expected lifecycle stage. */
  public LocalizationExperiment advanceTo(LocalizationExperimentStage nextStage) {
    Objects.requireNonNull(nextStage, "nextStage");
    if (nextStage.ordinal() != stage.ordinal() + 1) {
      throw new IllegalArgumentException(
          "experiment stage must advance exactly once from " + stage + " to " + nextStage);
    }
    return withStage(nextStage);
  }

  /** Return a copy with one deterministic metadata entry. */
  public LocalizationExperiment withMetadata(String key, String value) {
    requireText(key, "key");
    Objects.requireNonNull(value, "value");
    Map<String, String> changed = new TreeMap<>(metadata);
    changed.put(key, value);
    return new LocalizationExperiment(
        experimentId,
        displayName,
        profile,
        inputMode,
        sourceReference,
        createdAt,
        stage,
        changed);
  }

  private LocalizationExperiment withStage(LocalizationExperimentStage nextStage) {
    return new LocalizationExperiment(
        experimentId,
        displayName,
        profile,
        inputMode,
        sourceReference,
        createdAt,
        nextStage,
        metadata);
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
