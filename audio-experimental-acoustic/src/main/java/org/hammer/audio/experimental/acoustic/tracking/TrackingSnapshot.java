package org.hammer.audio.experimental.acoustic.tracking;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.acquisition.SynchronizationAssessment;
import org.hammer.audio.experimental.acoustic.TdoaConsistencyReport;
import org.hammer.audio.experimental.acoustic.wingbeat.ClassificationResult;

/**
 * Visualization-ready output of one {@link TrackingPipeline} step.
 *
 * @param sourceFrameIndex frame index of the analysed audio block
 * @param sourceTimestampNanos capture timestamp of the analysed audio block
 * @param clusters frequency clusters detected in this frame (after multi-peak + clustering),
 *     ordered by total magnitude (descending)
 * @param tracks currently active tracked sources (immutable, sorted by id)
 * @param processingNanos wall-clock processing time of the pipeline for this block
 * @param classificationResults optional per-track classification results, keyed by {@link
 *     TrackedSource#id()}; empty when no classifier has been applied
 * @param synchronization timing quality used for localization in this frame
 * @param tdoaConsistency physical and cycle-consistency evidence for pairwise TDOA estimates
 */
public record TrackingSnapshot(
    long sourceFrameIndex,
    long sourceTimestampNanos,
    List<FrequencyCluster> clusters,
    List<TrackedSource> tracks,
    long processingNanos,
    Map<Integer, ClassificationResult> classificationResults,
    SynchronizationAssessment synchronization,
    TdoaConsistencyReport tdoaConsistency) {

  /* Validate and defensively copy lists and maps. */
  public TrackingSnapshot {
    Objects.requireNonNull(clusters, "clusters");
    Objects.requireNonNull(tracks, "tracks");
    Objects.requireNonNull(classificationResults, "classificationResults");
    Objects.requireNonNull(synchronization, "synchronization");
    Objects.requireNonNull(tdoaConsistency, "tdoaConsistency");
    if (processingNanos < 0L) {
      throw new IllegalArgumentException("processingNanos must be >= 0");
    }
    clusters = List.copyOf(clusters);
    tracks = List.copyOf(tracks);
    classificationResults = Map.copyOf(classificationResults);
  }

  /** Creates a snapshot without explicit pair-consistency evidence. */
  public TrackingSnapshot(
      long sourceFrameIndex,
      long sourceTimestampNanos,
      List<FrequencyCluster> clusters,
      List<TrackedSource> tracks,
      long processingNanos,
      Map<Integer, ClassificationResult> classificationResults,
      SynchronizationAssessment synchronization) {
    this(
        sourceFrameIndex,
        sourceTimestampNanos,
        clusters,
        tracks,
        processingNanos,
        classificationResults,
        synchronization,
        TdoaConsistencyReport.notEvaluated());
  }

  /** Creates a shared-clock snapshot with explicit classification results. */
  public TrackingSnapshot(
      long sourceFrameIndex,
      long sourceTimestampNanos,
      List<FrequencyCluster> clusters,
      List<TrackedSource> tracks,
      long processingNanos,
      Map<Integer, ClassificationResult> classificationResults) {
    this(
        sourceFrameIndex,
        sourceTimestampNanos,
        clusters,
        tracks,
        processingNanos,
        classificationResults,
        SynchronizationAssessment.nominalSharedClock(),
        TdoaConsistencyReport.notEvaluated());
  }

  /**
   * Create a shared-clock snapshot without classification or pair-consistency results.
   *
   * @param sourceFrameIndex frame index of the analysed audio block
   * @param sourceTimestampNanos capture timestamp of the analysed audio block
   * @param clusters frequency clusters detected in this frame
   * @param tracks currently active tracked sources
   * @param processingNanos wall-clock processing time of the pipeline for this block
   */
  public TrackingSnapshot(
      long sourceFrameIndex,
      long sourceTimestampNanos,
      List<FrequencyCluster> clusters,
      List<TrackedSource> tracks,
      long processingNanos) {
    this(
        sourceFrameIndex,
        sourceTimestampNanos,
        clusters,
        tracks,
        processingNanos,
        Map.of(),
        SynchronizationAssessment.nominalSharedClock(),
        TdoaConsistencyReport.notEvaluated());
  }
}
