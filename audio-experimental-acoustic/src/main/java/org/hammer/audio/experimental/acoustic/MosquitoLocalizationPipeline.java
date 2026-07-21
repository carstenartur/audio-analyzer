package org.hammer.audio.experimental.acoustic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.acquisition.SynchronizationAssessment;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.experimental.acoustic.DelayAndSumBeamformer.BeamformingPoint;
import org.hammer.audio.geometry.LocalizationConstraint2D;
import org.hammer.audio.geometry.Vector2;

/** Example experimental pipeline combining frequency tracking, TDOA and beamforming. */
public final class MosquitoLocalizationPipeline {

  private final WingbeatFrequencyTracker frequencyTracker;
  private final TdoaEstimator tdoaEstimator;
  private final DelayAndSumBeamformer beamformer;
  private final List<Vector2> candidateGrid;
  private final int frequencyReferenceChannel;
  private final boolean aggregateFrequencyAcrossChannels;
  private final TdoaPairingMode tdoaPairingMode;

  /** Create a pipeline from interchangeable experimental stages. */
  public MosquitoLocalizationPipeline(
      WingbeatFrequencyTracker frequencyTracker,
      TdoaEstimator tdoaEstimator,
      DelayAndSumBeamformer beamformer,
      List<Vector2> candidateGrid) {
    this(
        frequencyTracker,
        tdoaEstimator,
        beamformer,
        candidateGrid,
        0,
        false,
        TdoaPairingMode.ALL_PAIRS);
  }

  /**
   * Create a configurable pipeline.
   *
   * <p>By default, production callers should prefer {@link TdoaPairingMode#ALL_PAIRS}. Reference
   * channel pairing is retained for controlled experiments where one calibrated microphone is the
   * timing anchor.
   */
  public MosquitoLocalizationPipeline(
      WingbeatFrequencyTracker frequencyTracker,
      TdoaEstimator tdoaEstimator,
      DelayAndSumBeamformer beamformer,
      List<Vector2> candidateGrid,
      int frequencyReferenceChannel,
      boolean aggregateFrequencyAcrossChannels,
      TdoaPairingMode tdoaPairingMode) {
    this.frequencyTracker = Objects.requireNonNull(frequencyTracker, "frequencyTracker");
    this.tdoaEstimator = Objects.requireNonNull(tdoaEstimator, "tdoaEstimator");
    this.beamformer = Objects.requireNonNull(beamformer, "beamformer");
    this.candidateGrid = List.copyOf(Objects.requireNonNull(candidateGrid, "candidateGrid"));
    if (this.candidateGrid.isEmpty()) {
      throw new IllegalArgumentException("candidateGrid must not be empty");
    }
    if (this.candidateGrid.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("candidateGrid must not contain null entries");
    }
    if (frequencyReferenceChannel < 0) {
      throw new IllegalArgumentException("frequencyReferenceChannel must be >= 0");
    }
    this.frequencyReferenceChannel = frequencyReferenceChannel;
    this.aggregateFrequencyAcrossChannels = aggregateFrequencyAcrossChannels;
    this.tdoaPairingMode = Objects.requireNonNull(tdoaPairingMode, "tdoaPairingMode");
  }

  /** Analyze one synchronized multichannel block. */
  public AcousticLocalizationSnapshot analyze(AudioBlock block, MicrophoneArray array) {
    Objects.requireNonNull(block, "block");
    Objects.requireNonNull(array, "array");
    if (block.channels() != array.channels()) {
      throw new IllegalArgumentException("block channel count must match microphone array");
    }
    if (frequencyReferenceChannel >= array.channels()) {
      throw new IllegalArgumentException(
          "frequencyReferenceChannel must exist in microphone array");
    }
    SynchronizationAssessment synchronization = synchronizationAssessment(block, array);
    if (!synchronization.usable()) {
      throw new UnusableSynchronizationException(String.join(" ", synchronization.diagnostics()));
    }
    SpectralPeak peak = trackFrequency(block, array);
    List<TdoaEstimate> estimates = new ArrayList<>();
    List<LocalizationConstraint2D> constraints = new ArrayList<>();
    for (int[] pair : channelPairs(array.channels())) {
      TdoaEstimate estimate = tdoaEstimator.estimate(block, array, pair[0], pair[1]);
      estimates.add(estimate);
      constraints.add(estimate.asConstraint());
    }
    List<BeamformingPoint> heatmap = beamformer.scan(block, array, candidateGrid);
    Vector2 bestPosition = beamformer.best(block, array, candidateGrid).positionMeters();
    return new AcousticLocalizationSnapshot(
        block.frameIndex(),
        block.timestampNanos(),
        peak,
        estimates,
        constraints,
        heatmap,
        bestPosition,
        synchronization);
  }

  private SynchronizationAssessment synchronizationAssessment(
      AudioBlock block, MicrophoneArray array) {
    if (tdoaEstimator instanceof SynchronizationAwareTdoaEstimator aware) {
      return aware.synchronizationAssessment(block, array);
    }
    return SynchronizationAssessment.nominalSharedClock();
  }

  private SpectralPeak trackFrequency(AudioBlock block, MicrophoneArray array) {
    if (!aggregateFrequencyAcrossChannels) {
      return frequencyTracker.track(block, frequencyReferenceChannel);
    }
    SpectralPeak bestPeak = frequencyTracker.track(block, 0);
    for (int channel = 1; channel < array.channels(); channel++) {
      SpectralPeak peak = frequencyTracker.track(block, channel);
      if (peak.confidence() > bestPeak.confidence()
          || (peak.confidence() == bestPeak.confidence()
              && peak.magnitude() > bestPeak.magnitude())) {
        bestPeak = peak;
      }
    }
    return bestPeak;
  }

  private List<int[]> channelPairs(int channels) {
    List<int[]> pairs = new ArrayList<>();
    if (tdoaPairingMode == TdoaPairingMode.REFERENCE_CHANNEL) {
      for (int channel = 0; channel < channels; channel++) {
        if (channel != frequencyReferenceChannel) {
          pairs.add(new int[] {frequencyReferenceChannel, channel});
        }
      }
      return pairs;
    }
    for (int first = 0; first < channels; first++) {
      for (int second = first + 1; second < channels; second++) {
        pairs.add(new int[] {first, second});
      }
    }
    return pairs;
  }

  /** Pairing strategy for experimental TDOA estimation. */
  public enum TdoaPairingMode {
    /** Estimate all unique microphone pairs for maximum geometric constraints. */
    ALL_PAIRS,
    /** Estimate pairs from one calibrated reference channel to every other channel. */
    REFERENCE_CHANNEL
  }
}
