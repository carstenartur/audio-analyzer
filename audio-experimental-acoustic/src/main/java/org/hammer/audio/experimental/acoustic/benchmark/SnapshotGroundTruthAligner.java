package org.hammer.audio.experimental.acoustic.benchmark;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.experimental.acoustic.scenario.AcousticGroundTruth;
import org.hammer.audio.experimental.acoustic.scenario.Scenario;
import org.hammer.audio.experimental.acoustic.scenario.ScenarioSource;
import org.hammer.audio.experimental.acoustic.scenario.ScenarioTrajectory;
import org.hammer.audio.experimental.acoustic.tracking.TrackedSource;
import org.hammer.audio.experimental.acoustic.tracking.TrackingSnapshot;
import org.hammer.audio.geometry.Vector2;

/** Align tracking snapshots to scenario ground truth using position and frequency hints. */
public final class SnapshotGroundTruthAligner {

  private static final double MAX_POSITION_ALIGNMENT_METERS = 1.5;
  private static final double MAX_FREQUENCY_ALIGNMENT_HZ = 120.0;
  private static final Comparator<GroundTruthObservation> TRUTH_ORDER =
      Comparator.comparing((GroundTruthObservation observation) -> observation.source().sourceId())
          .thenComparing(
              GroundTruthObservation::expectedFrequencyHz,
              Comparator.nullsLast(Double::compareTo))
          .thenComparing(
              observation ->
                  observation.expectedPositionMeters() != null
                      ? observation.expectedPositionMeters().x()
                      : null,
              Comparator.nullsLast(Double::compareTo))
          .thenComparing(
              observation ->
                  observation.expectedPositionMeters() != null
                      ? observation.expectedPositionMeters().y()
                      : null,
              Comparator.nullsLast(Double::compareTo));
  private static final Comparator<IndexedTrack> TRACK_ORDER =
      Comparator.comparingInt((IndexedTrack track) -> track.trackedSource().id())
          .thenComparingDouble(track -> track.trackedSource().frequencyHz())
          .thenComparingDouble(track -> track.trackedSource().positionMeters().x())
          .thenComparingDouble(track -> track.trackedSource().positionMeters().y())
          .thenComparingInt(IndexedTrack::originalIndex);

  /** Align one snapshot to the supplied scenario truth. */
  public SnapshotAlignment align(Scenario scenario, TrackingSnapshot snapshot) {
    Objects.requireNonNull(scenario, "scenario");
    Objects.requireNonNull(snapshot, "snapshot");
    double timestampSeconds = snapshot.sourceTimestampNanos() / 1.0e9;
    List<GroundTruthObservation> truthSamples = truthSamplesAt(scenario, timestampSeconds);
    List<IndexedTrack> indexedTracks = sortedTracks(snapshot.tracks());
    Assignment assignment = bestAssignment(truthSamples, indexedTracks);

    List<AlignedSourceObservation> matchedSources = new ArrayList<>(assignment.pairs.size());
    boolean[] matchedTruthIndexes = new boolean[truthSamples.size()];
    boolean[] matchedTrackIndexes = new boolean[snapshot.tracks().size()];
    for (MatchPair pair : assignment.pairs) {
      GroundTruthObservation sample = truthSamples.get(pair.truthIndex);
      IndexedTrack track = indexedTracks.get(pair.trackIndex);
      matchedTruthIndexes[pair.truthIndex] = true;
      matchedTrackIndexes[track.originalIndex()] = true;
      matchedSources.add(new AlignedSourceObservation(sample, track.trackedSource()));
    }

    List<GroundTruthObservation> missingSources = new ArrayList<>();
    for (int i = 0; i < truthSamples.size(); i++) {
      if (!matchedTruthIndexes[i]) {
        missingSources.add(truthSamples.get(i));
      }
    }

    List<TrackedSource> spuriousTracks = new ArrayList<>();
    for (int i = 0; i < snapshot.tracks().size(); i++) {
      if (!matchedTrackIndexes[i]) {
        spuriousTracks.add(snapshot.tracks().get(i));
      }
    }

    return new SnapshotAlignment(timestampSeconds, matchedSources, missingSources, spuriousTracks);
  }

  private static List<GroundTruthObservation> truthSamplesAt(Scenario scenario, double timestampSeconds) {
    List<GroundTruthObservation> samples = new ArrayList<>(scenario.sources().size());
    for (ScenarioSource source : scenario.sources()) {
      ScenarioTrajectory trajectory = source.trajectory();
      Vector2 expectedPosition =
          trajectory != null
              ? interpolate(trajectory.positions(), trajectory.timestamps(), timestampSeconds)
              : null;
      Vector2 expectedVelocity =
          trajectory != null && trajectory.velocities() != null
              ? interpolate(trajectory.velocities(), trajectory.timestamps(), timestampSeconds)
              : null;
      AcousticGroundTruth acoustic = source.acousticProperties();
      Double expectedFrequency = acoustic != null ? acoustic.fundamentalFrequencyHz() : null;
      samples.add(new GroundTruthObservation(source, expectedPosition, expectedVelocity, expectedFrequency));
    }
    samples.sort(TRUTH_ORDER);
    return samples;
  }

  private static Vector2 interpolate(
      List<Vector2> values, List<Double> timestamps, double timestampSeconds) {
    if (timestampSeconds <= timestamps.get(0)) {
      return values.get(0);
    }
    int lastIndex = timestamps.size() - 1;
    if (timestampSeconds >= timestamps.get(lastIndex)) {
      return values.get(lastIndex);
    }
    for (int i = 1; i < timestamps.size(); i++) {
      double upperTime = timestamps.get(i);
      if (timestampSeconds <= upperTime) {
        double lowerTime = timestamps.get(i - 1);
        double alpha = (timestampSeconds - lowerTime) / (upperTime - lowerTime);
        Vector2 lower = values.get(i - 1);
        Vector2 upper = values.get(i);
        return lower.plus(upper.minus(lower).scale(alpha));
      }
    }
    return values.get(lastIndex);
  }

  private static List<IndexedTrack> sortedTracks(List<TrackedSource> tracks) {
    List<IndexedTrack> indexedTracks = new ArrayList<>(tracks.size());
    for (int i = 0; i < tracks.size(); i++) {
      indexedTracks.add(new IndexedTrack(i, tracks.get(i)));
    }
    indexedTracks.sort(TRACK_ORDER);
    return List.copyOf(indexedTracks);
  }

  private static Assignment bestAssignment(
      List<GroundTruthObservation> truthSamples, List<IndexedTrack> tracks) {
    return searchAssignments(
        truthSamples,
        tracks,
        0,
        new boolean[tracks.size()],
        new ArrayList<>(),
        0.0,
        new Assignment(List.of(), 0, Double.POSITIVE_INFINITY));
  }

  private static Assignment searchAssignments(
      List<GroundTruthObservation> truthSamples,
      List<IndexedTrack> tracks,
      int truthIndex,
      boolean[] usedTracks,
      List<MatchPair> currentPairs,
      double currentCost,
      Assignment best) {
    if (truthIndex >= truthSamples.size()) {
      Assignment candidate =
          new Assignment(List.copyOf(currentPairs), currentPairs.size(), currentCost);
      return betterOf(best, candidate);
    }

    best =
        searchAssignments(
            truthSamples, tracks, truthIndex + 1, usedTracks, currentPairs, currentCost, best);
    GroundTruthObservation truth = truthSamples.get(truthIndex);
    for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) {
      if (usedTracks[trackIndex]) {
        continue;
      }
      double cost = alignmentCost(truth, tracks.get(trackIndex).trackedSource());
      if (!Double.isFinite(cost)) {
        continue;
      }
      usedTracks[trackIndex] = true;
      currentPairs.add(new MatchPair(truthIndex, trackIndex));
      best =
          searchAssignments(
              truthSamples,
              tracks,
              truthIndex + 1,
              usedTracks,
              currentPairs,
              currentCost + cost,
              best);
      currentPairs.remove(currentPairs.size() - 1);
      usedTracks[trackIndex] = false;
    }
    return best;
  }

  private static Assignment betterOf(Assignment first, Assignment second) {
    if (second.matchedCount > first.matchedCount) {
      return second;
    }
    if (second.matchedCount < first.matchedCount) {
      return first;
    }
    if (second.totalCost < first.totalCost) {
      return second;
    }
    if (second.totalCost > first.totalCost) {
      return first;
    }
    return comparePairs(second.pairs, first.pairs) < 0 ? second : first;
  }

  private static int comparePairs(List<MatchPair> first, List<MatchPair> second) {
    int comparisonLength = Math.min(first.size(), second.size());
    for (int i = 0; i < comparisonLength; i++) {
      MatchPair firstPair = first.get(i);
      MatchPair secondPair = second.get(i);
      int truthComparison = Integer.compare(firstPair.truthIndex(), secondPair.truthIndex());
      if (truthComparison != 0) {
        return truthComparison;
      }
      int trackComparison = Integer.compare(firstPair.trackIndex(), secondPair.trackIndex());
      if (trackComparison != 0) {
        return trackComparison;
      }
    }
    return Integer.compare(first.size(), second.size());
  }

  private static double alignmentCost(GroundTruthObservation truth, TrackedSource track) {
    if (!truth.hasAlignmentTruth()) {
      return Double.POSITIVE_INFINITY;
    }
    double positionError = Double.NaN;
    if (truth.expectedPositionMeters() != null) {
      positionError = truth.expectedPositionMeters().distanceTo(track.positionMeters());
      if (positionError > MAX_POSITION_ALIGNMENT_METERS && truth.expectedFrequencyHz() == null) {
        return Double.POSITIVE_INFINITY;
      }
    }
    double frequencyError = Double.NaN;
    if (truth.expectedFrequencyHz() != null) {
      frequencyError = Math.abs(track.frequencyHz() - truth.expectedFrequencyHz());
      if (frequencyError > MAX_FREQUENCY_ALIGNMENT_HZ && truth.expectedPositionMeters() == null) {
        return Double.POSITIVE_INFINITY;
      }
    }
    if (truth.expectedPositionMeters() != null
        && truth.expectedFrequencyHz() != null
        && positionError > MAX_POSITION_ALIGNMENT_METERS
        && frequencyError > MAX_FREQUENCY_ALIGNMENT_HZ) {
      return Double.POSITIVE_INFINITY;
    }
    double cost = 0.0;
    if (Double.isFinite(positionError)) {
      cost += positionError;
    }
    if (Double.isFinite(frequencyError)) {
      cost += frequencyError / 100.0;
    }
    return cost;
  }

  private record IndexedTrack(int originalIndex, TrackedSource trackedSource) {}

  private record MatchPair(int truthIndex, int trackIndex) {}

  private record Assignment(List<MatchPair> pairs, int matchedCount, double totalCost) {}
}
