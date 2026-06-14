package org.hammer.audio.experimental.acoustic.scenario;

import java.util.Map;
import java.util.Objects;

/**
 * Classification ground truth for a single acoustic source in a scenario.
 *
 * <p>All string fields ({@link #species()}, {@link #sex()}, {@link #age()}, {@link
 * #feedingStatus()}) may be {@code null} to represent unknown classification. The {@link #labels()}
 * map allows arbitrary key-value annotations and is never {@code null} (use an empty map when no
 * custom labels are available).
 *
 * <p>Use {@link #ofSpecies(String)} to create a minimal instance, or {@link #unknown()} when no
 * classification is available.
 *
 * @param species optional species label
 * @param sex optional sex label
 * @param age optional age label
 * @param feedingStatus optional feeding-status label
 * @param labels arbitrary additional classification labels (never {@code null})
 */
public record ClassificationGroundTruth(
    String species, String sex, String age, String feedingStatus, Map<String, String> labels) {

  /* Validate and defensively copy the custom label map. */
  public ClassificationGroundTruth {
    Objects.requireNonNull(labels, "labels");
    labels = Map.copyOf(labels);
  }

  /**
   * Create a classification truth that only specifies the species.
   *
   * @param species species identifier; must not be {@code null}
   */
  public static ClassificationGroundTruth ofSpecies(String species) {
    Objects.requireNonNull(species, "species");
    return new ClassificationGroundTruth(species, null, null, null, Map.of());
  }

  /**
   * Create a classification truth for synthetic fixtures while keeping biological species labels
   * separate.
   *
   * @param customLabel synthetic fixture label; must not be {@code null}
   */
  public static ClassificationGroundTruth synthetic(String customLabel) {
    Objects.requireNonNull(customLabel, "customLabel");
    return new ClassificationGroundTruth(
        "unknown", null, null, null, Map.of("customLabel", customLabel));
  }

  /** Create a classification truth with no known classification fields. */
  public static ClassificationGroundTruth unknown() {
    return new ClassificationGroundTruth(null, null, null, null, Map.of());
  }
}
