package org.hammer.audio.experimental.acoustic.simulation;

import org.hammer.audio.experimental.acoustic.scenario.AcousticGroundTruth;
import org.hammer.audio.geometry.Vector2;

/** Deterministic 2D acoustic emitter used by the room simulator. */
public interface AcousticEmitter2D {

  /** Start position in meters. */
  Vector2 startMeters();

  /** Constant source velocity in meters per second. */
  Vector2 velocityMetersPerSecond();

  /** Fundamental emitted frequency in Hz. */
  double frequencyHz();

  /** Source amplitude in the normalized range {@code [0, 1]}. */
  double amplitude();

  /** Position at simulation time {@code seconds}. */
  default Vector2 positionAt(double seconds) {
    return startMeters().plus(velocityMetersPerSecond().scale(seconds));
  }

  /** Sample emitted at simulation time {@code seconds}. */
  double sampleAt(double seconds);

  /** Sample emitted at simulation time {@code seconds} with a Doppler-shifted frequency. */
  double sampleAt(double seconds, double observedFrequencyHz);

  /** Acoustic metadata exported for scenario ground truth. */
  default AcousticGroundTruth acousticGroundTruth() {
    return AcousticGroundTruth.ofFrequency(frequencyHz());
  }
}
