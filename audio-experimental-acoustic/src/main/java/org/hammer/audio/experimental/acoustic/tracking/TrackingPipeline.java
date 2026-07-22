package org.hammer.audio.experimental.acoustic.tracking;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.acquisition.SynchronizationAssessment;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.experimental.acoustic.DelayAndSumBeamformer;
import org.hammer.audio.experimental.acoustic.SynchronizationAwareTdoaEstimator;
import org.hammer.audio.experimental.acoustic.TdoaConsistencyReport;
import org.hammer.audio.experimental.acoustic.TdoaEstimate;
import org.hammer.audio.experimental.acoustic.TdoaEstimator;
import org.hammer.audio.experimental.acoustic.TdoaPairConsistencyAnalyzer;
import org.hammer.audio.experimental.acoustic.UnusableSynchronizationException;
import org.hammer.audio.experimental.acoustic.doppler.FrequencyTrack;
import org.hammer.audio.experimental.acoustic.doppler.MultiSensorDopplerEstimator;
import org.hammer.audio.experimental.acoustic.doppler.RadialVelocityEstimate;
import org.hammer.audio.experimental.acoustic.doppler.SimpleMultiSensorDopplerEstimator;
import org.hammer.audio.experimental.acoustic.doppler.SourceObservation;
import org.hammer.audio.experimental.acoustic.doppler.VelocityReconstructor;
import org.hammer.audio.geometry.Vector2;
import org.hammer.audio.geometry.Vector3;

/**
 * Coherent real-time multi-source tracking pipeline.
 *
 * <p>For each input {@link AudioBlock} the pipeline executes the following stages, in order:
 *
 * <ol>
 *   <li>Per-channel multi-peak FFT detection ({@link MultiPeakDetector}).
 *   <li>Cross-channel frequency clustering ({@link FrequencyClusterer}).
 *   <li>One pairwise TDOA pass across all microphones plus physical/cycle-consistency analysis.
 *   <li>Localization via delay-and-sum beamforming ({@link DelayAndSumBeamformer}) over a
 *       caller-supplied 2D candidate grid; one best position per cluster.
 *   <li>Temporal tracking via {@link SourceTracker} (identity persistence + Kalman smoothing).
 * </ol>
 *
 * <p>Real-time readiness is supported via {@link FrameSchedule} and {@link ProcessingBudget}. The
 * pipeline never allocates unbounded data structures per frame; the only per-frame allocations are
 * the small lists carried in the produced {@link TrackingSnapshot}.
 *
 * <p>Instances are single-threaded and stateful: the {@link SourceTracker} carries information
 * between frames. Create one pipeline per processing thread.
 */
public final class TrackingPipeline {

  private static final double DEFAULT_SPEED_OF_SOUND_METERS_PER_SECOND = 343.0;
  private static final double DEFAULT_CYCLE_TOLERANCE_SAMPLES = 0.75;
  private static final double DEFAULT_PHYSICAL_TOLERANCE_SAMPLES = 0.25;
  private static final double FREQUENCY_TRACK_MATCH_TOLERANCE_HZ = 25.0;
  private static final int FREQUENCY_TRACK_HISTORY_FRAMES = 8;
  private static final double FREQUENCY_TRACK_SMOOTHING_ALPHA = 0.2;
  private static final long FREQUENCY_TRACK_MAX_IDLE_FRAMES = 32L;
  private static final int FREQUENCY_TRACK_MAX_ACTIVE = 64;

  private final MultiPeakDetector peakDetector;
  private final FrequencyClusterer clusterer;
  private final TdoaEstimator tdoaEstimator;
  private final TdoaPairConsistencyAnalyzer consistencyAnalyzer;
  private final DelayAndSumBeamformer beamformer;
  private final SourceTracker tracker;
  private final MultiSensorDopplerEstimator dopplerEstimator;
  private final VelocityReconstructor velocityReconstructor;
  private final List<Vector2> candidateGrid;
  private final FrameSchedule schedule;
  private final List<PipelineFrequencyTrack> frequencyTracks = new ArrayList<>();
  private List<DopplerDiagnostics> lastDopplerDiagnostics = List.of();
  private TdoaConsistencyReport lastTdoaConsistency = TdoaConsistencyReport.notEvaluated();
  private int nextFrequencyTrackId;

  /** Configure a pipeline. {@code schedule} may be {@code null} to disable budget reporting. */
  public TrackingPipeline(
      MultiPeakDetector peakDetector,
      FrequencyClusterer clusterer,
      TdoaEstimator tdoaEstimator,
      DelayAndSumBeamformer beamformer,
      SourceTracker tracker,
      List<Vector2> candidateGrid,
      FrameSchedule schedule) {
    this(
        peakDetector,
        clusterer,
        new LocalizationComponents(
            tdoaEstimator,
            new TdoaPairConsistencyAnalyzer(
                DEFAULT_SPEED_OF_SOUND_METERS_PER_SECOND,
                DEFAULT_CYCLE_TOLERANCE_SAMPLES,
                DEFAULT_PHYSICAL_TOLERANCE_SAMPLES),
            beamformer,
            SimpleMultiSensorDopplerEstimator.withDefaults(),
            new VelocityReconstructor()),
        tracker,
        candidateGrid,
        schedule);
  }

  /** Configure a pipeline with explicit localization and Doppler components. */
  public TrackingPipeline(
      MultiPeakDetector peakDetector,
      FrequencyClusterer clusterer,
      LocalizationComponents localization,
      SourceTracker tracker,
      List<Vector2> candidateGrid,
      FrameSchedule schedule) {
    this.peakDetector = Objects.requireNonNull(peakDetector, "peakDetector");
    this.clusterer = Objects.requireNonNull(clusterer, "clusterer");
    LocalizationComponents requiredLocalization =
        Objects.requireNonNull(localization, "localization");
    this.tdoaEstimator = requiredLocalization.tdoaEstimator();
    this.consistencyAnalyzer = requiredLocalization.consistencyAnalyzer();
    this.beamformer = requiredLocalization.beamformer();
    this.tracker = Objects.requireNonNull(tracker, "tracker");
    this.dopplerEstimator = requiredLocalization.dopplerEstimator();
    this.velocityReconstructor = requiredLocalization.velocityReconstructor();
    Objects.requireNonNull(candidateGrid, "candidateGrid");
    if (candidateGrid.isEmpty()) {
      throw new IllegalArgumentException("candidateGrid must not be empty");
    }
    this.candidateGrid = List.copyOf(candidateGrid);
    this.schedule = schedule;
  }

  /** Process one synchronized multichannel block and emit a tracking snapshot. */
  public TrackingSnapshot process(AudioBlock block, MicrophoneArray array) {
    Objects.requireNonNull(block, "block");
    Objects.requireNonNull(array, "array");
    if (block.channels() != array.channels()) {
      throw new IllegalArgumentException("block channel count must match microphone array");
    }
    SynchronizationAssessment synchronization = synchronizationAssessment(block, array);
    if (!synchronization.usable()) {
      throw new UnusableSynchronizationException(String.join(" ", synchronization.diagnostics()));
    }
    long startNanos = System.nanoTime();
    List<List<DetectedPeak>> perChannel = peakDetector.detectAllChannels(block);
    List<FrequencyCluster> clusters = clusterer.clusterPerChannel(perChannel);
    List<TdoaEstimate> pairEstimates = estimatePairs(block, array);
    TdoaConsistencyReport consistency =
        consistencyAnalyzer.analyze(array, pairEstimates, block.format().sampleRate());
    lastTdoaConsistency = consistency;
    evictStaleFrequencyTracks(block.frameIndex());

    List<SourceTracker.Observation> observations = new ArrayList<>(clusters.size());
    List<DopplerDiagnostics> dopplerDiagnostics = new ArrayList<>(clusters.size());
    Set<Integer> usedFrequencyTracks = new HashSet<>();
    for (FrequencyCluster cluster : clusters) {
      DelayAndSumBeamformer.BeamformingPoint best = beamformer.best(block, array, candidateGrid);
      PipelineFrequencyTrack pipelineFrequencyTrack =
          frequencyTrackFor(cluster.centerFrequencyHz(), block.frameIndex(), usedFrequencyTracks);
      FrequencyTrack frequencyTrack = pipelineFrequencyTrack.track;
      double stableFrequency = frequencyTrack.update(cluster.centerFrequencyHz());
      pipelineFrequencyTrack.lastObservedFrequencyHz = cluster.centerFrequencyHz();
      SourceObservation sourceObservation =
          new SourceObservation(
              cluster.centerFrequencyHz(), stableFrequency, best.positionMeters(), cluster.peaks());
      List<RadialVelocityEstimate> radialVelocities =
          dopplerEstimator.estimateRadialVelocities(sourceObservation);
      Vector3 velocity =
          velocityReconstructor.reconstruct(radialVelocities, array, best.positionMeters());
      double radialVelocity = velocityReconstructor.fusedRadialVelocity(radialVelocities);
      double radialVelocityStdDev =
          velocityReconstructor.radialVelocityStandardDeviation(radialVelocities);
      observations.add(
          new SourceTracker.Observation(
              stableFrequency,
              cluster.centerFrequencyHz(),
              best.positionMeters(),
              velocity,
              radialVelocity,
              frequencyTrack.variance(),
              radialVelocityStdDev));
      dopplerDiagnostics.add(
          new DopplerDiagnostics(
              stableFrequency,
              cluster.centerFrequencyHz(),
              best.positionMeters(),
              radialVelocities,
              frequencyTrack.variance(),
              radialVelocityStdDev));
    }
    lastDopplerDiagnostics = List.copyOf(dopplerDiagnostics);

    double timestampSeconds = block.timestampNanos() / 1.0e9;
    List<TrackedSource> tracks = tracker.update(block.frameIndex(), timestampSeconds, observations);
    long processingNanos = System.nanoTime() - startNanos;
    return new TrackingSnapshot(
        block.frameIndex(),
        block.timestampNanos(),
        clusters,
        tracks,
        processingNanos,
        Map.of(),
        synchronization,
        consistency);
  }

  /** Snapshot the underlying tracker without consuming a new block. */
  public List<TrackedSource> currentTracks() {
    return tracker.snapshot();
  }

  /** Snapshot of the last per-source Doppler diagnostics emitted by {@link #process}. */
  public List<DopplerDiagnostics> currentDopplerDiagnostics() {
    return lastDopplerDiagnostics;
  }

  /** Physical and cycle-consistency evidence from the most recently processed frame. */
  public TdoaConsistencyReport currentTdoaConsistency() {
    return lastTdoaConsistency;
  }

  /** Reset internal tracking state. */
  public void reset() {
    tracker.reset();
    frequencyTracks.clear();
    lastDopplerDiagnostics = List.of();
    lastTdoaConsistency = TdoaConsistencyReport.notEvaluated();
    nextFrequencyTrackId = 0;
  }

  /** Frame schedule the pipeline was configured against, or {@code null} when unspecified. */
  public FrameSchedule schedule() {
    return schedule;
  }

  private List<TdoaEstimate> estimatePairs(AudioBlock block, MicrophoneArray array) {
    List<TdoaEstimate> estimates = new ArrayList<>();
    for (int first = 0; first < array.channels(); first++) {
      for (int second = first + 1; second < array.channels(); second++) {
        estimates.add(tdoaEstimator.estimate(block, array, first, second));
      }
    }
    return List.copyOf(estimates);
  }

  private SynchronizationAssessment synchronizationAssessment(
      AudioBlock block, MicrophoneArray array) {
    if (tdoaEstimator instanceof SynchronizationAwareTdoaEstimator aware) {
      return aware.synchronizationAssessment(block, array);
    }
    return SynchronizationAssessment.nominalSharedClock();
  }

  private PipelineFrequencyTrack frequencyTrackFor(
      double frequencyHz, long frameIndex, Set<Integer> usedFrequencyTrackIds) {
    PipelineFrequencyTrack best = null;
    double bestDistance = FREQUENCY_TRACK_MATCH_TOLERANCE_HZ;
    for (PipelineFrequencyTrack candidate : frequencyTracks) {
      if (usedFrequencyTrackIds.contains(candidate.id)) {
        continue;
      }
      double distance = Math.abs(candidate.lastObservedFrequencyHz - frequencyHz);
      if (distance <= bestDistance) {
        bestDistance = distance;
        best = candidate;
      }
    }
    if (best == null) {
      best =
          new PipelineFrequencyTrack(
              nextFrequencyTrackId++,
              new FrequencyTrack(FREQUENCY_TRACK_HISTORY_FRAMES, FREQUENCY_TRACK_SMOOTHING_ALPHA),
              frequencyHz,
              frameIndex);
      frequencyTracks.add(best);
      trimFrequencyTrackCapacity();
    }
    best.lastTouchedFrameIndex = frameIndex;
    usedFrequencyTrackIds.add(best.id);
    return best;
  }

  private void evictStaleFrequencyTracks(long frameIndex) {
    frequencyTracks.removeIf(
        track -> frameIndex - track.lastTouchedFrameIndex > FREQUENCY_TRACK_MAX_IDLE_FRAMES);
  }

  private void trimFrequencyTrackCapacity() {
    if (frequencyTracks.size() <= FREQUENCY_TRACK_MAX_ACTIVE) {
      return;
    }
    while (frequencyTracks.size() > FREQUENCY_TRACK_MAX_ACTIVE) {
      int oldestIndex = 0;
      for (int index = 1; index < frequencyTracks.size(); index++) {
        if (frequencyTracks.get(index).lastTouchedFrameIndex
            < frequencyTracks.get(oldestIndex).lastTouchedFrameIndex) {
          oldestIndex = index;
        }
      }
      frequencyTracks.remove(oldestIndex);
    }
  }

  /**
   * Interchangeable localization and Doppler stages configured as one coherent pipeline component.
   *
   * @param tdoaEstimator pairwise time-difference estimator
   * @param consistencyAnalyzer physical and cycle-consistency analyzer
   * @param beamformer candidate-grid localizer
   * @param dopplerEstimator per-microphone radial-velocity estimator
   * @param velocityReconstructor fused velocity reconstructor
   */
  public record LocalizationComponents(
      TdoaEstimator tdoaEstimator,
      TdoaPairConsistencyAnalyzer consistencyAnalyzer,
      DelayAndSumBeamformer beamformer,
      MultiSensorDopplerEstimator dopplerEstimator,
      VelocityReconstructor velocityReconstructor) {

    // Validate all localization stages.
    public LocalizationComponents {
      Objects.requireNonNull(tdoaEstimator, "tdoaEstimator");
      Objects.requireNonNull(consistencyAnalyzer, "consistencyAnalyzer");
      Objects.requireNonNull(beamformer, "beamformer");
      Objects.requireNonNull(dopplerEstimator, "dopplerEstimator");
      Objects.requireNonNull(velocityReconstructor, "velocityReconstructor");
    }
  }

  /** Frequency reference state matched by nearest observed frequency with per-frame exclusivity. */
  private static final class PipelineFrequencyTrack {
    final int id;
    final FrequencyTrack track;
    double lastObservedFrequencyHz;
    long lastTouchedFrameIndex;

    PipelineFrequencyTrack(
        int id, FrequencyTrack track, double lastObservedFrequencyHz, long lastTouchedFrameIndex) {
      this.id = id;
      this.track = track;
      this.lastObservedFrequencyHz = lastObservedFrequencyHz;
      this.lastTouchedFrameIndex = lastTouchedFrameIndex;
    }
  }

  /**
   * Per-source Doppler diagnostics from the most recently processed frame.
   *
   * @param referenceFrequencyHz smoothed source reference frequency
   * @param observedFrequencyHz current observed source frequency
   * @param positionMeters estimated source position used by the Doppler stage
   * @param perMicrophoneRadialVelocities immutable radial-velocity estimates
   * @param frequencyVarianceHzSquared variance of the tracked frequency reference
   * @param radialVelocityStdDevMetersPerSecond spread of the radial-velocity estimates
   */
  public record DopplerDiagnostics(
      double referenceFrequencyHz,
      double observedFrequencyHz,
      Vector2 positionMeters,
      List<RadialVelocityEstimate> perMicrophoneRadialVelocities,
      double frequencyVarianceHzSquared,
      double radialVelocityStdDevMetersPerSecond) {

    /** Validate diagnostic fields. */
    public DopplerDiagnostics {
      Objects.requireNonNull(positionMeters, "positionMeters");
      Objects.requireNonNull(perMicrophoneRadialVelocities, "perMicrophoneRadialVelocities");
      perMicrophoneRadialVelocities = List.copyOf(perMicrophoneRadialVelocities);
      if (!(referenceFrequencyHz > 0.0) || !Double.isFinite(referenceFrequencyHz)) {
        throw new IllegalArgumentException("referenceFrequencyHz must be finite and > 0");
      }
      if (!Double.isFinite(observedFrequencyHz) || observedFrequencyHz < 0.0) {
        throw new IllegalArgumentException("observedFrequencyHz must be finite and >= 0");
      }
      if (!Double.isFinite(frequencyVarianceHzSquared) || frequencyVarianceHzSquared < 0.0) {
        throw new IllegalArgumentException("frequencyVarianceHzSquared must be finite and >= 0");
      }
      if (!Double.isFinite(radialVelocityStdDevMetersPerSecond)
          || radialVelocityStdDevMetersPerSecond < 0.0) {
        throw new IllegalArgumentException(
            "radialVelocityStdDevMetersPerSecond must be finite and >= 0");
      }
    }
  }
}
