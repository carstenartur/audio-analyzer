package org.hammer.audio.experimental.acoustic.benchmark;

import java.util.Objects;
import org.hammer.audio.experimental.acoustic.scenario.ScenarioSource;
import org.hammer.audio.geometry.Vector2;

/**
 * Ground-truth state for one source at one benchmark timestamp.
 *
 * @param source source metadata
 * @param expectedPositionMeters expected source position in meters, or {@code null} if unavailable
 * @param expectedVelocityMetersPerSecond expected source velocity, or {@code null} if unavailable
 * @param expectedFrequencyHz expected source frequency in hertz, or {@code null} if unavailable
 */
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
