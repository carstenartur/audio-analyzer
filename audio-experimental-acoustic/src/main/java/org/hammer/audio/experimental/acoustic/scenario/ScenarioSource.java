package org.hammer.audio.experimental.acoustic.scenario;

import java.util.Objects;

/**
 * Ground truth for a single acoustic source in a scenario.
 *
 * <p>{@link #trajectory()}, {@link #acousticProperties()} and {@link #labels()} may be {@code null}
 * to represent partial ground truth. This is expected for real-world datasets where position or
 * classification may not be known.
 *
 * <p>Use {@link Builder} for incremental construction when not all fields are available upfront.
 *
 * @param sourceId unique source identifier within the scenario
 * @param sourceType source category (for example {@code "mosquito"} or {@code "bird"})
 * @param trajectory source trajectory ground truth (nullable for partial truth)
 * @param acousticProperties source acoustic ground truth (nullable for partial truth)
 * @param labels source classification ground truth (nullable for partial truth)
 */
public record ScenarioSource(
    String sourceId,
    String sourceType,
    ScenarioTrajectory trajectory,
    AcousticGroundTruth acousticProperties,
    ClassificationGroundTruth labels) {

  /* Validate required fields. */
  public ScenarioSource {
    Objects.requireNonNull(sourceId, "sourceId");
    Objects.requireNonNull(sourceType, "sourceType");
    if (sourceId.isBlank()) {
      throw new IllegalArgumentException("sourceId must not be blank");
    }
    if (sourceType.isBlank()) {
      throw new IllegalArgumentException("sourceType must not be blank");
    }
  }

  /**
   * Create a {@link Builder} for the given source id and type.
   *
   * @param sourceId unique source identifier within the scenario; must not be blank
   * @param sourceType category string (e.g. {@code "mosquito"}, {@code "bird"}); must not be blank
   */
  public static Builder builder(String sourceId, String sourceType) {
    return new Builder(sourceId, sourceType);
  }

  /** Incremental builder for {@link ScenarioSource}. */
  public static final class Builder {

    private final String sourceId;
    private final String sourceType;
    private ScenarioTrajectory trajectoryValue;
    private AcousticGroundTruth acousticPropertiesValue;
    private ClassificationGroundTruth labelsValue;

    private Builder(String sourceId, String sourceType) {
      this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
      this.sourceType = Objects.requireNonNull(sourceType, "sourceType");
    }

    /** Set the trajectory ground truth; {@code null} is permitted for partial truth. */
    public Builder trajectory(ScenarioTrajectory trajectory) {
      this.trajectoryValue = trajectory;
      return this;
    }

    /** Set the acoustic ground truth; {@code null} is permitted for partial truth. */
    public Builder acousticProperties(AcousticGroundTruth acousticProperties) {
      this.acousticPropertiesValue = acousticProperties;
      return this;
    }

    /** Set the classification ground truth; {@code null} is permitted for partial truth. */
    public Builder labels(ClassificationGroundTruth labels) {
      this.labelsValue = labels;
      return this;
    }

    /** Build the {@link ScenarioSource}. */
    public ScenarioSource build() {
      return new ScenarioSource(
          sourceId, sourceType, trajectoryValue, acousticPropertiesValue, labelsValue);
    }
  }
}
