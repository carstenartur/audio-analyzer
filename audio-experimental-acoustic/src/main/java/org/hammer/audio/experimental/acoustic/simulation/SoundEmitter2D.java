package org.hammer.audio.experimental.acoustic.simulation;

import org.hammer.audio.geometry.Vector2;

/** Synthetic moving tonal emitter for repeatable acoustic localization experiments. */
public record SoundEmitter2D(
    Vector2 startMeters, Vector2 velocityMetersPerSecond, double frequencyHz, double amplitude)
    implements AcousticEmitter2D {

  /** Create a synthetic emitter. */
  public SoundEmitter2D {
    if (startMeters == null || velocityMetersPerSecond == null) {
      throw new IllegalArgumentException("positions must not be null");
    }
    if (!(frequencyHz > 0.0) || !Double.isFinite(frequencyHz)) {
      throw new IllegalArgumentException("frequencyHz must be finite and > 0");
    }
    if (amplitude < 0.0 || amplitude > 1.0 || !Double.isFinite(amplitude)) {
      throw new IllegalArgumentException("amplitude must be finite and in [0,1]");
    }
  }

  @Override
  public Vector2 positionAt(double seconds) {
    return startMeters.plus(velocityMetersPerSecond.scale(seconds));
  }

  @Override
  public double sampleAt(double seconds) {
    return amplitude * Math.sin(2.0 * Math.PI * frequencyHz * seconds);
  }

  @Override
  public double sampleAt(double seconds, double observedFrequencyHz) {
    if (!(observedFrequencyHz > 0.0) || !Double.isFinite(observedFrequencyHz)) {
      throw new IllegalArgumentException("observedFrequencyHz must be finite and > 0");
    }
    return amplitude * Math.sin(2.0 * Math.PI * observedFrequencyHz * seconds);
  }
}
