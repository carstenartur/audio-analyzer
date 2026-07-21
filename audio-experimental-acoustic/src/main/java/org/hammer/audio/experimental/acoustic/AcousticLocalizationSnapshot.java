package org.hammer.audio.experimental.acoustic;

import java.util.List;
import java.util.Objects;
import org.hammer.audio.acquisition.SynchronizationAssessment;
import org.hammer.audio.experimental.acoustic.DelayAndSumBeamformer.BeamformingPoint;
import org.hammer.audio.geometry.LocalizationConstraint2D;
import org.hammer.audio.geometry.Vector2;

/** Visualization-ready output from the experimental acoustic localization pipeline. */
public record AcousticLocalizationSnapshot(
    long sourceFrameIndex,
    long sourceTimestampNanos,
    SpectralPeak trackedFrequency,
    List<TdoaEstimate> tdoaEstimates,
    List<LocalizationConstraint2D> constraints,
    List<BeamformingPoint> heatmap,
    Vector2 estimatedPositionMeters,
    SynchronizationAssessment synchronization) {

  /** Create an immutable acoustic-localization snapshot. */
  public AcousticLocalizationSnapshot {
    if (trackedFrequency == null || estimatedPositionMeters == null) {
      throw new IllegalArgumentException(
          "trackedFrequency and estimatedPositionMeters must not be null");
    }
    tdoaEstimates = List.copyOf(Objects.requireNonNull(tdoaEstimates, "tdoaEstimates"));
    constraints = List.copyOf(Objects.requireNonNull(constraints, "constraints"));
    heatmap = List.copyOf(Objects.requireNonNull(heatmap, "heatmap"));
    Objects.requireNonNull(synchronization, "synchronization");
  }

  /** Creates a snapshot for a declared shared-clock multichannel path. */
  public AcousticLocalizationSnapshot(
      long sourceFrameIndex,
      long sourceTimestampNanos,
      SpectralPeak trackedFrequency,
      List<TdoaEstimate> tdoaEstimates,
      List<LocalizationConstraint2D> constraints,
      List<BeamformingPoint> heatmap,
      Vector2 estimatedPositionMeters) {
    this(
        sourceFrameIndex,
        sourceTimestampNanos,
        trackedFrequency,
        tdoaEstimates,
        constraints,
        heatmap,
        estimatedPositionMeters,
        SynchronizationAssessment.nominalSharedClock());
  }
}
