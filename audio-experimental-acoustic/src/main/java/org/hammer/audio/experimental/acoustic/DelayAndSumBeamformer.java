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
    int frames = block.frames();
    double energy = 0.0;
    for (int frame = 0; frame < frames; frame++) {
      double sum = 0.0;
      int contributors = 0;
      for (Microphone mic : array.microphones()) {
        // The captured sample already contains the propagation delay. Advance each channel by the
        // candidate's predicted delay so equal source-emission times add coherently.
        int alignedIndex = frame + delaySamples(block, mic, candidate);
        if (alignedIndex >= 0 && alignedIndex < frames) {
          sum += block.channelView(mic.channel())[alignedIndex];
          contributors++;
        }
      }
      if (contributors > 0) {
        double average = sum / contributors;
        energy += average * average;
      }
    }
    return frames > 0 ? energy / frames : 0.0;
  }

  private int delaySamples(AudioBlock block, Microphone mic, Vector2 candidate) {
    double seconds = mic.positionMeters().distanceTo(candidate) / speedOfSoundMetersPerSecond;
    return (int) Math.round(seconds * block.format().sampleRate());
  }

  /** Beamforming score at one candidate point. */
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
