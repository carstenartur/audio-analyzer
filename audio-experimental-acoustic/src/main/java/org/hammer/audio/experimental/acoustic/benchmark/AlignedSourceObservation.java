package org.hammer.audio.experimental.acoustic.benchmark;

import java.util.Objects;
import org.hammer.audio.experimental.acoustic.scenario.ScenarioSource;
import org.hammer.audio.experimental.acoustic.tracking.TrackedSource;
import org.hammer.audio.geometry.Vector2;

/** One measured track aligned to one scenario source at a benchmark timestamp. */
public record AlignedSourceObservation(
    ScenarioSource groundTruth,
    Vector2 expectedPositionMeters,
    Vector2 expectedVelocityMetersPerSecond,
    TrackedSource trackedSource) {

  public AlignedSourceObservation {
    Objects.requireNonNull(groundTruth, "groundTruth");
    Objects.requireNonNull(trackedSource, "trackedSource");
  }
}
