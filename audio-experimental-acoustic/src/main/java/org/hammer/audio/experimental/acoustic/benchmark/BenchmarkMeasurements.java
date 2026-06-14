package org.hammer.audio.experimental.acoustic.benchmark;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.experimental.acoustic.tracking.TrackingSnapshot;

/** Measured outputs that can be compared against a scenario ground truth. */
public record BenchmarkMeasurements(
    MicrophoneArray array,
    List<TrackingSnapshot> snapshots,
    Map<String, ClassificationPrediction> classificationPredictions) {

  public BenchmarkMeasurements {
    Objects.requireNonNull(array, "array");
    Objects.requireNonNull(snapshots, "snapshots");
    Objects.requireNonNull(classificationPredictions, "classificationPredictions");
    snapshots = List.copyOf(snapshots);
    classificationPredictions = Map.copyOf(classificationPredictions);
  }

  /** Create measurements without classification outputs. */
  public static BenchmarkMeasurements of(MicrophoneArray array, List<TrackingSnapshot> snapshots) {
    return new BenchmarkMeasurements(array, snapshots, Map.of());
  }
}
