package org.hammer.audio.experimental.acoustic.feature.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Ordered collection of per-feature evaluation entries produced by {@link
 * FeatureEvaluationService}.
 *
 * @param entries evaluation entries, one per feature, in extraction order; must not be empty
 */
public record FeatureEvaluationReport(List<FeatureEvaluationEntry> entries) {

  /** Validate and defensively copy the entry list. */
  public FeatureEvaluationReport {
    Objects.requireNonNull(entries, "entries");
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("entries must not be empty");
    }
    entries = List.copyOf(entries);
  }

  /**
   * Return the entry for the given feature name, or an empty optional when not present.
   *
   * @param featureName feature name to look up; must not be {@code null}
   * @return matching entry, or empty
   */
  public Optional<FeatureEvaluationEntry> entry(String featureName) {
    Objects.requireNonNull(featureName, "featureName");
    return entries.stream().filter(e -> featureName.equals(e.featureName())).findFirst();
  }

  /**
   * Return the ordered list of feature names present in this report.
   *
   * @return feature names in extraction order; never {@code null}
   */
  public List<String> featureNames() {
    List<String> names = new ArrayList<>(entries.size());
    for (FeatureEvaluationEntry entry : entries) {
      names.add(entry.featureName());
    }
    return List.copyOf(names);
  }
}
