package org.hammer.audio.experimental.acoustic;

import java.util.ArrayList;
import java.util.List;
import org.hammer.audio.acquisition.Microphone;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.geometry.Vector2;

/** Basic delay-and-sum beamformer over a caller-supplied 2D candidate grid. */
public final class DelayAndSumBeamformer {

  private final double speedOfSoundMetersPerSecond;

  /** Create a beamformer with a propagation speed. */
  public DelayAndSumBeamformer(double speedOfSoundMetersPerSecond) {
    if (!(speedOfSoundMetersPerSecond > 0.0) || !Double.isFinite(speedOfSoundMetersPerSecond)) {
      throw new IllegalArgumentException("speedOfSoundMetersPerSecond must be finite and > 0");
    }
    this.speedOfSoundMetersPerSecond = speedOfSoundMetersPerSecond;
  }

  /** Score candidate positions and return a heatmap sorted in input order. */
  public List<BeamformingPoint> scan(
      AudioBlock block, MicrophoneArray array, List<Vector2> candidates) {
    List<BeamformingPoint> points = new ArrayList<>(candidates.size());
    for (Vector2 candidate : candidates) {
      points.add(new BeamformingPoint(candidate, scoreCandidate(block, array, candidate)));
    }
    return List.copyOf(points);
  }

  /** Return the highest-energy candidate. */
  public BeamformingPoint best(AudioBlock block, MicrophoneArray array, List<Vector2> candidates) {
    return scan(block, array, candidates).stream()
        .max((left, right) -> Double.compare(left.energy(), right.energy()))
        .orElseThrow(() -> new IllegalArgumentException("candidates must not be empty"));
  }

  private double scoreCandidate(AudioBlock block, MicrophoneArray array, Vector2 candidate) {
    List<Microphone> microphones = array.microphones();
    int[] relativeDelays = relativeDelaySamples(block, microphones, candidate);
    int maximumDelay = 0;
    float[][] channels = new float[microphones.size()][];
    for (int index = 0; index < microphones.size(); index++) {
      maximumDelay = Math.max(maximumDelay, relativeDelays[index]);
      channels[index] = block.channelView(microphones.get(index).channel());
    }
    int commonFrames = block.frames() - maximumDelay;
    if (commonFrames <= 0) {
      return 0.0;
    }

    double energy = 0.0;
    for (int frame = 0; frame < commonFrames; frame++) {
      double sum = 0.0;
      for (int microphoneIndex = 0; microphoneIndex < microphones.size(); microphoneIndex++) {
        // Captured channels already contain propagation delay. Advance each channel only by its
        // delay relative to the earliest microphone. A common absolute delay is not observable in
        // passive localization and must not change the score of a finite signal block.
        sum += channels[microphoneIndex][frame + relativeDelays[microphoneIndex]];
      }
      double average = sum / microphones.size();
      energy += average * average;
    }
    return energy / commonFrames;
  }

  private int[] relativeDelaySamples(
      AudioBlock block, List<Microphone> microphones, Vector2 candidate) {
    double[] distances = new double[microphones.size()];
    double minimumDistance = Double.POSITIVE_INFINITY;
    for (int index = 0; index < microphones.size(); index++) {
      distances[index] = microphones.get(index).positionMeters().distanceTo(candidate);
      minimumDistance = Math.min(minimumDistance, distances[index]);
    }
    int[] delays = new int[microphones.size()];
    double samplesPerMeter = block.format().sampleRate() / speedOfSoundMetersPerSecond;
    for (int index = 0; index < delays.length; index++) {
      delays[index] = (int) Math.round((distances[index] - minimumDistance) * samplesPerMeter);
    }
    return delays;
  }

  /**
   * Beamforming score at one candidate point.
   *
   * @param positionMeters candidate position in meters
   * @param energy normalized delay-and-sum energy
   */
  public record BeamformingPoint(Vector2 positionMeters, double energy) {

    /** Create a score point. */
    public BeamformingPoint {
      if (positionMeters == null) {
        throw new IllegalArgumentException("positionMeters must not be null");
      }
      if (!Double.isFinite(energy) || energy < 0.0) {
        throw new IllegalArgumentException("energy must be finite and >= 0");
      }
    }
  }
}
