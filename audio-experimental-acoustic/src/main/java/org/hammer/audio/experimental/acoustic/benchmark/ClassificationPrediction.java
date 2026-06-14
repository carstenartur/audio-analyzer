package org.hammer.audio.experimental.acoustic.benchmark;

import java.util.Map;
import java.util.Objects;

/**
 * Predicted classification labels produced by a benchmarked algorithm.
 *
 * @param species predicted species label
 * @param sex predicted sex label
 * @param age predicted age label
 * @param feedingStatus predicted feeding-status label
 * @param labels additional synthetic or domain-specific labels
 */
public record ClassificationPrediction(
    String species, String sex, String age, String feedingStatus, Map<String, String> labels) {

  public ClassificationPrediction {
    Objects.requireNonNull(labels, "labels");
    labels = Map.copyOf(labels);
  }

  /** Create a prediction that only specifies the species. */
  public static ClassificationPrediction ofSpecies(String species) {
    Objects.requireNonNull(species, "species");
    return new ClassificationPrediction(species, null, null, null, Map.of());
  }

  /** Create a synthetic-label prediction for fixture benchmarks. */
  public static ClassificationPrediction synthetic(String customLabel) {
    Objects.requireNonNull(customLabel, "customLabel");
    return new ClassificationPrediction(null, null, null, null, Map.of("customLabel", customLabel));
  }

  /** Create a prediction with no populated labels. */
  public static ClassificationPrediction unknown() {
    return new ClassificationPrediction(null, null, null, null, Map.of());
  }
}
