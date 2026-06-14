package org.hammer.audio.experimental.acoustic.benchmark;

import java.util.Objects;
import org.hammer.audio.experimental.acoustic.tracking.TrackedSource;

/**
 * One measured track aligned to one scenario source at a benchmark timestamp.
 *
 * @param groundTruth matched scenario-ground-truth observation
 * @param trackedSource matched tracked source
 */
public record AlignedSourceObservation(
    GroundTruthObservation groundTruth, TrackedSource trackedSource) {

  public AlignedSourceObservation {
    Objects.requireNonNull(groundTruth, "groundTruth");
    Objects.requireNonNull(trackedSource, "trackedSource");
  }
}
