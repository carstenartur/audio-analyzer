package org.hammer.audio.experimental.acoustic.scenario;

import java.util.List;
import java.util.Objects;

/**
 * Ground-truth scenario model describing the actual state of a simulation, synthetic dataset or
 * benchmark.
 *
 * <p>A {@code Scenario} is the root of the ground-truth representation. It bundles:
 *
 * <ul>
 *   <li>{@link #id()} — unique scenario identifier
 *   <li>{@link #description()} — human-readable description of the scenario
 *   <li>{@link #sources()} — ground truth for every acoustic source present
 *   <li>{@link #environment()} — environmental context (speed of sound, etc.)
 * </ul>
 *
 * <p>Evaluation algorithms compare measured localization, tracking, frequency or classification
 * output against the ground truth carried by this model. The same representation supports synthetic
 * scenarios, public datasets and real-world recordings; some source fields may be {@code null} when
 * the truth is not known.
 *
 * @param id unique scenario identifier
 * @param description human-readable scenario description
 * @param sources per-source ground truth entries
 * @param environment environmental context shared by all sources
 */
public record Scenario(
    String id, String description, List<ScenarioSource> sources, ScenarioEnvironment environment) {

  /* Validate and defensively copy the source list. */
  public Scenario {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(sources, "sources");
    Objects.requireNonNull(environment, "environment");
    if (id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    if (sources.isEmpty()) {
      throw new IllegalArgumentException("sources must not be empty");
    }
    sources = List.copyOf(sources);
  }
}
