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
              GroundTruthObservation::expectedFrequencyHz, Comparator.nullsLast(Double::compareTo))
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
  private static final long COST_SCALE = 1_000_000L;

  /**
   * Align one snapshot to the supplied scenario truth.
   *
   * <p>This overload expects {@link TrackingSnapshot#sourceTimestampNanos()} to already be
   * normalized to scenario-relative time, such as by subtracting the first snapshot timestamp in a
   * benchmark run.
   */
  public SnapshotAlignment align(Scenario scenario, TrackingSnapshot snapshot) {
    return align(scenario, snapshot, 0L);
  }

  /**
   * Align one snapshot to the supplied scenario truth using a scenario start timestamp.
   *
   * <p>The scenario time is computed as {@code (snapshot.sourceTimestampNanos() -
   * scenarioStartTimestampNanos) / 1e9}. Use this overload when snapshots carry capture timestamps
   * from live or recorded pipelines instead of scenario-relative nanoseconds.
   */
  public SnapshotAlignment align(
      Scenario scenario, TrackingSnapshot snapshot, long scenarioStartTimestampNanos) {
    Objects.requireNonNull(scenario, "scenario");
    Objects.requireNonNull(snapshot, "snapshot");
    double timestampSeconds =
        (snapshot.sourceTimestampNanos() - scenarioStartTimestampNanos) / 1.0e9;
    List<GroundTruthObservation> truthSamples = truthSamplesAt(scenario, timestampSeconds);
    List<IndexedTrack> indexedTracks = sortedTracks(snapshot.tracks());
    Assignment assignment = bestAssignment(truthSamples, indexedTracks);

    List<AlignedSourceObservation> matchedSources = new ArrayList<>(assignment.pairs().size());
    boolean[] matchedTruthIndexes = new boolean[truthSamples.size()];
    boolean[] matchedTrackIndexes = new boolean[snapshot.tracks().size()];
    for (MatchPair pair : assignment.pairs()) {
      GroundTruthObservation sample = truthSamples.get(pair.truthIndex());
      IndexedTrack track = indexedTracks.get(pair.trackIndex());
      matchedTruthIndexes[pair.truthIndex()] = true;
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

  private static List<GroundTruthObservation> truthSamplesAt(
      Scenario scenario, double timestampSeconds) {
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
      samples.add(
          new GroundTruthObservation(
              source, expectedPosition, expectedVelocity, expectedFrequency));
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
    int truthCount = truthSamples.size();
    int trackCount = tracks.size();
    if (truthCount == 0 || trackCount == 0) {
      return new Assignment(List.of());
    }

    int nodeCount = 2 + truthCount + trackCount;
    int sourceNode = 0;
    int sinkNode = nodeCount - 1;
    List<List<Edge>> graph = emptyGraph(nodeCount);
    addSourceEdges(graph, sourceNode, truthCount);
    addCandidateEdges(graph, truthSamples, tracks, truthCount, trackCount);
    addSinkEdges(graph, sinkNode, truthCount, trackCount);

    long totalScaledCost = 0L;
    int matchedCount = 0;
    while (true) {
      PathResult path = shortestAugmentingPath(graph, sourceNode, sinkNode);
      if (!path.reachable()) {
        break;
      }
      augment(graph, path, sinkNode);
      totalScaledCost += path.distanceToSink();
      matchedCount++;
    }
    return new Assignment(collectMatchedPairs(graph, matchedCount));
  }

  private static List<List<Edge>> emptyGraph(int nodeCount) {
    List<List<Edge>> graph = new ArrayList<>(nodeCount);
    for (int i = 0; i < nodeCount; i++) {
      graph.add(new ArrayList<>());
    }
    return graph;
  }

  private static void addSourceEdges(List<List<Edge>> graph, int sourceNode, int truthCount) {
    for (int truthIndex = 0; truthIndex < truthCount; truthIndex++) {
      addEdge(graph, sourceNode, truthNode(truthIndex), 1, 0L, -1, -1);
    }
  }

  private static void addCandidateEdges(
      List<List<Edge>> graph,
      List<GroundTruthObservation> truthSamples,
      List<IndexedTrack> tracks,
      int truthCount,
      int trackCount) {
    for (int truthIndex = 0; truthIndex < truthSamples.size(); truthIndex++) {
      addCandidateEdgesForTruth(
          graph, truthSamples.get(truthIndex), tracks, truthCount, trackCount, truthIndex);
    }
  }

  private static void addCandidateEdgesForTruth(
      List<List<Edge>> graph,
      GroundTruthObservation truth,
      List<IndexedTrack> tracks,
      int truthCount,
      int trackCount,
      int truthIndex) {
    for (int trackIndex = 0; trackIndex < trackCount; trackIndex++) {
      double cost = alignmentCost(truth, tracks.get(trackIndex).trackedSource());
      if (!Double.isFinite(cost)) {
        continue;
      }
      addEdge(
          graph,
          truthNode(truthIndex),
          trackNode(truthCount, trackIndex),
          1,
          scaledCost(cost, trackIndex, trackCount),
          truthIndex,
          trackIndex);
    }
  }

  private static void addSinkEdges(
      List<List<Edge>> graph, int sinkNode, int truthCount, int trackCount) {
    for (int trackIndex = 0; trackIndex < trackCount; trackIndex++) {
      addEdge(graph, trackNode(truthCount, trackIndex), sinkNode, 1, 0L, -1, -1);
    }
  }

  private static List<MatchPair> collectMatchedPairs(List<List<Edge>> graph, int matchedCount) {
    List<MatchPair> pairs = new ArrayList<>(matchedCount);
    for (List<Edge> edges : graph) {
      for (Edge edge : edges) {
        if (edge.truthIndexValue() >= 0
            && edge.trackIndexValue() >= 0
            && edge.remainingCapacityValue() == 0) {
          pairs.add(new MatchPair(edge.truthIndexValue(), edge.trackIndexValue()));
        }
      }
    }
    pairs.sort(
        Comparator.comparingInt(MatchPair::truthIndex).thenComparingInt(MatchPair::trackIndex));
    return pairs;
  }

  private static int truthNode(int truthIndex) {
    return 1 + truthIndex;
  }

  private static int trackNode(int truthCount, int trackIndex) {
    return 1 + truthCount + trackIndex;
  }

  private static long scaledCost(double cost, int trackIndex, int trackCount) {
    // Preserve the alignment cost as the primary ordering and use the track index only as a
    // deterministic tie-breaker when two candidate matches quantize to the same cost.
    return Math.round(cost * COST_SCALE) * (trackCount + 1L) + trackIndex;
  }

  private static void addEdge(
      List<List<Edge>> graph,
      int from,
      int to,
      int capacity,
      long cost,
      int truthIndex,
      int trackIndex) {
    Edge forward = new Edge(to, graph.get(to).size(), capacity, cost, truthIndex, trackIndex);
    Edge reverse = new Edge(from, graph.get(from).size(), 0, -cost, -1, -1);
    graph.get(from).add(forward);
    graph.get(to).add(reverse);
  }

  private static PathResult shortestAugmentingPath(List<List<Edge>> graph, int source, int sink) {
    int nodeCount = graph.size();
    long[] distance = new long[nodeCount];
    int[] previousNode = new int[nodeCount];
    int[] previousEdge = new int[nodeCount];
    boolean[] inQueue = new boolean[nodeCount];
    for (int i = 0; i < nodeCount; i++) {
      distance[i] = Long.MAX_VALUE;
      previousNode[i] = -1;
      previousEdge[i] = -1;
    }
    distance[source] = 0L;

    List<Integer> queue = new ArrayList<>();
    queue.add(source);
    inQueue[source] = true;
    int queueIndex = 0;
    while (queueIndex < queue.size()) {
      int node = queue.get(queueIndex);
      queueIndex++;
      inQueue[node] = false;
      List<Edge> edges = graph.get(node);
      for (int edgeIndex = 0; edgeIndex < edges.size(); edgeIndex++) {
        Edge edge = edges.get(edgeIndex);
        if (edge.remainingCapacityValue() <= 0) {
          continue;
        }
        long nextDistance = distance[node] + edge.edgeCostValue();
        if (nextDistance < distance[edge.targetNodeValue()]) {
          distance[edge.targetNodeValue()] = nextDistance;
          previousNode[edge.targetNodeValue()] = node;
          previousEdge[edge.targetNodeValue()] = edgeIndex;
          if (!inQueue[edge.targetNodeValue()]) {
            queue.add(edge.targetNodeValue());
            inQueue[edge.targetNodeValue()] = true;
          }
        }
      }
    }
    return new PathResult(
        distance[sink] != Long.MAX_VALUE, distance[sink], previousNode, previousEdge);
  }

  private static void augment(List<List<Edge>> graph, PathResult path, int sink) {
    for (int node = sink; path.previousNode()[node] >= 0; node = path.previousNode()[node]) {
      int previousNode = path.previousNode()[node];
      Edge forward = graph.get(previousNode).get(path.previousEdge()[node]);
      Edge reverse = graph.get(node).get(forward.reverseEdgeIndexValue());
      forward.remainingCapacityValue(forward.remainingCapacityValue() - 1);
      reverse.remainingCapacityValue(reverse.remainingCapacityValue() + 1);
    }
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

  private static final class IndexedTrack {
    private final int originalIndexValue;
    private final TrackedSource trackedSourceValue;

    private IndexedTrack(int originalIndex, TrackedSource trackedSource) {
      this.originalIndexValue = originalIndex;
      this.trackedSourceValue = trackedSource;
    }

    private int originalIndex() {
      return originalIndexValue;
    }

    private TrackedSource trackedSource() {
      return trackedSourceValue;
    }
  }

  private static final class MatchPair {
    private final int truthIndexValue;
    private final int trackIndexValue;

    private MatchPair(int truthIndex, int trackIndex) {
      this.truthIndexValue = truthIndex;
      this.trackIndexValue = trackIndex;
    }

    private int truthIndex() {
      return truthIndexValue;
    }

    private int trackIndex() {
      return trackIndexValue;
    }
  }

  private static final class Assignment {
    private final List<MatchPair> pairsValue;

    private Assignment(List<MatchPair> pairs) {
      this.pairsValue = pairs;
    }

    private List<MatchPair> pairs() {
      return pairsValue;
    }
  }

  private static final class PathResult {
    private final boolean reachableValue;
    private final long distanceToSinkValue;
    private final int[] previousNodeValue;
    private final int[] previousEdgeValue;

    private PathResult(
        boolean reachable, long distanceToSink, int[] previousNode, int[] previousEdge) {
      this.reachableValue = reachable;
      this.distanceToSinkValue = distanceToSink;
      this.previousNodeValue = previousNode;
      this.previousEdgeValue = previousEdge;
    }

    private boolean reachable() {
      return reachableValue;
    }

    private long distanceToSink() {
      return distanceToSinkValue;
    }

    private int[] previousNode() {
      return previousNodeValue;
    }

    private int[] previousEdge() {
      return previousEdgeValue;
    }
  }

  /**
   * Residual-network edge for the min-cost max-flow truth-to-track assignment search.
   *
   * <p>{@code reverseIndex} points at the paired reverse edge, {@code truthIndex}/{@code
   * trackIndex} identify only real truth-to-track match edges, and {@code capacity} stays mutable
   * so the residual graph can be updated after each augmenting path while the other edge metadata
   * remains fixed.
   */
  private static final class Edge {
    private final int targetNode;
    private final int reverseEdgeIndex;
    private int remainingCapacity;
    private final long edgeCost;
    private final int truthIndex;
    private final int trackIndex;

    private Edge(
        int to, int reverseIndex, int capacity, long cost, int truthIndex, int trackIndex) {
      this.targetNode = to;
      this.reverseEdgeIndex = reverseIndex;
      this.remainingCapacity = capacity;
      this.edgeCost = cost;
      this.truthIndex = truthIndex;
      this.trackIndex = trackIndex;
    }

    private int targetNodeValue() {
      return targetNode;
    }

    private int reverseEdgeIndexValue() {
      return reverseEdgeIndex;
    }

    private int remainingCapacityValue() {
      return remainingCapacity;
    }

    private void remainingCapacityValue(int capacity) {
      this.remainingCapacity = capacity;
    }

    private long edgeCostValue() {
      return edgeCost;
    }

    private int truthIndexValue() {
      return truthIndex;
    }

    private int trackIndexValue() {
      return trackIndex;
    }
  }
}
