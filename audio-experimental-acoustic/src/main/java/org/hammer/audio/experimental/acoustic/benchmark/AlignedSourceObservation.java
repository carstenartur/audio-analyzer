package org.hammer.audio.experimental.acoustic.benchmark;

import java.util.Objects;
import org.hammer.audio.experimental.acoustic.tracking.TrackedSource;

/** One measured track aligned to one scenario source at a benchmark timestamp. */
public record AlignedSourceObservation(
    GroundTruthObservation groundTruth, TrackedSource trackedSource) {

  public AlignedSourceObservation {
    Objects.requireNonNull(groundTruth, "groundTruth");
    Objects.requireNonNull(trackedSource, "trackedSource");
  }
}
