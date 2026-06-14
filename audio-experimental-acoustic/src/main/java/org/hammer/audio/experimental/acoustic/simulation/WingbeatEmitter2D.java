package org.hammer.audio.experimental.acoustic.simulation;

import org.hammer.audio.experimental.acoustic.scenario.AcousticGroundTruth;
import org.hammer.audio.geometry.Vector2;

/**
 * Deterministic emitter that reuses {@link WingbeatSignalParameters} and {@link
 * WingbeatSignalGenerator} for synthetic wingbeat simulation.
 */
public record WingbeatEmitter2D(
    Vector2 startMeters,
    Vector2 velocityMetersPerSecond,
    double amplitude,
    WingbeatSignalParameters params,
    double sampleRate,
    long randomSeed)
    implements AcousticEmitter2D {

  /** Create a wingbeat-backed emitter. */
  public WingbeatEmitter2D {
    if (startMeters == null || velocityMetersPerSecond == null) {
      throw new IllegalArgumentException("positions must not be null");
    }
    if (amplitude < 0.0 || amplitude > 1.0 || !Double.isFinite(amplitude)) {
      throw new IllegalArgumentException("amplitude must be finite and in [0,1]");
    }
    if (params == null) {
      throw new IllegalArgumentException("params must not be null");
    }
    if (!(sampleRate > 0.0) || !Double.isFinite(sampleRate)) {
      throw new IllegalArgumentException("sampleRate must be finite and > 0");
    }
  }

  @Override
  public double frequencyHz() {
    return params.fundamentalFrequencyHz();
  }

  @Override
  public double sampleAt(double seconds) {
    return amplitude
        * WingbeatSignalGenerator.sampleAtTime(params, randomSeed, sampleRate, seconds);
  }

  @Override
  public double sampleAt(double seconds, double observedFrequencyHz) {
    if (!(observedFrequencyHz > 0.0) || !Double.isFinite(observedFrequencyHz)) {
      throw new IllegalArgumentException("observedFrequencyHz must be finite and > 0");
    }
    double frequencyScale = observedFrequencyHz / params.fundamentalFrequencyHz();
    return amplitude
        * WingbeatSignalGenerator.sampleAtTime(
            params, randomSeed, sampleRate, seconds, frequencyScale);
  }

  @Override
  public AcousticGroundTruth acousticGroundTruth() {
    return params.toGroundTruth();
  }
}
