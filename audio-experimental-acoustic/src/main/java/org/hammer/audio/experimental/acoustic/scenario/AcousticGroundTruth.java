package org.hammer.audio.experimental.acoustic.scenario;

import java.util.List;

/**
 * Acoustic ground truth for a single source in a scenario.
 *
 * <p>Only {@link #fundamentalFrequencyHz()} is required. All other fields may be {@code null} to
 * represent partial ground truth (e.g. harmonics or modulation parameters that are unknown for a
 * real-world recording).
 *
 * <p>Use {@link #ofFrequency(double)} when only the fundamental frequency is known.
 */
public record AcousticGroundTruth(
    double fundamentalFrequencyHz,
    List<Double> harmonics,
    Double modulation,
    Double jitter,
    Double drift,
    Double signalPower) {

  /** Validate required fields and defensively copy {@link #harmonics()} when provided. */
  public AcousticGroundTruth {
    if (!(fundamentalFrequencyHz > 0.0) || !Double.isFinite(fundamentalFrequencyHz)) {
      throw new IllegalArgumentException("fundamentalFrequencyHz must be finite and > 0");
    }
    harmonics = harmonics != null ? List.copyOf(harmonics) : null;
    if (modulation != null && !Double.isFinite(modulation)) {
      throw new IllegalArgumentException("modulation must be finite when provided");
    }
    if (jitter != null && (!Double.isFinite(jitter) || jitter < 0.0)) {
      throw new IllegalArgumentException("jitter must be finite and >= 0 when provided");
    }
    if (drift != null && !Double.isFinite(drift)) {
      throw new IllegalArgumentException("drift must be finite when provided");
    }
    if (signalPower != null && !Double.isFinite(signalPower)) {
      throw new IllegalArgumentException("signalPower must be finite when provided");
    }
  }

  /**
   * Create acoustic truth with only the fundamental frequency known.
   *
   * @param fundamentalFrequencyHz emitted fundamental frequency in Hz; must be finite and positive
   */
  public static AcousticGroundTruth ofFrequency(double fundamentalFrequencyHz) {
    return new AcousticGroundTruth(fundamentalFrequencyHz, null, null, null, null, null);
  }
}
