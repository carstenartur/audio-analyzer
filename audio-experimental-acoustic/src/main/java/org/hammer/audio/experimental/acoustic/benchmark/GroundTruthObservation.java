package org.hammer.audio.experimental.acoustic.benchmark;

import java.util.Objects;
import org.hammer.audio.experimental.acoustic.scenario.ScenarioSource;
import org.hammer.audio.geometry.Vector2;

/** Ground-truth state for one source at one benchmark timestamp. */
public record GroundTruthObservation(
    ScenarioSource source,
    Vector2 expectedPositionMeters,
    Vector2 expectedVelocityMetersPerSecond,
    Double expectedFrequencyHz) {

  public GroundTruthObservation {
    Objects.requireNonNull(source, "source");
  }

  public boolean hasPositionTruth() {
    return expectedPositionMeters != null;
  }

  public boolean hasFrequencyTruth() {
    return expectedFrequencyHz != null;
  }

  public boolean hasDopplerTruth() {
    return expectedPositionMeters != null && expectedVelocityMetersPerSecond != null;
  }

  public boolean hasAlignmentTruth() {
    return hasPositionTruth() || hasFrequencyTruth();
  }
}
