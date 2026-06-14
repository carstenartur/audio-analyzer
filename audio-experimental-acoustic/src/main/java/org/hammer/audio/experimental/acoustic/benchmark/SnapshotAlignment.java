package org.hammer.audio.experimental.acoustic.benchmark;

import java.util.List;
import java.util.Objects;
import org.hammer.audio.experimental.acoustic.scenario.ScenarioSource;
import org.hammer.audio.experimental.acoustic.tracking.TrackedSource;

/** Alignment result for one tracking snapshot against scenario truth. */
public record SnapshotAlignment(
    double timestampSeconds,
    List<AlignedSourceObservation> matchedSources,
    List<ScenarioSource> missingSources,
    List<TrackedSource> spuriousTracks) {

  public SnapshotAlignment {
    if (!Double.isFinite(timestampSeconds) || timestampSeconds < 0.0) {
      throw new IllegalArgumentException("timestampSeconds must be finite and >= 0");
    }
    Objects.requireNonNull(matchedSources, "matchedSources");
    Objects.requireNonNull(missingSources, "missingSources");
    Objects.requireNonNull(spuriousTracks, "spuriousTracks");
    matchedSources = List.copyOf(matchedSources);
    missingSources = List.copyOf(missingSources);
    spuriousTracks = List.copyOf(spuriousTracks);
  }
}
