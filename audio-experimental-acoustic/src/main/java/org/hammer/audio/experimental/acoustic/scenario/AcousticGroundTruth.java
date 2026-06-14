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
 *
 * @param fundamentalFrequencyHz emitted fundamental frequency in Hz
 * @param harmonicCount optional number of harmonic partials
 * @param harmonics optional harmonic amplitude profile
 * @param modulationFrequencyHz optional modulation frequency in Hz
 * @param modulationDepth optional modulation depth in [0, 1]
 * @param jitter optional maximum signed per-sample frequency deviation in Hz
 * @param drift optional frequency drift parameter
 * @param noiseAmplitude optional additive white-noise amplitude in [0, 1]
 * @param signalPower optional signal power parameter
 */
public record AcousticGroundTruth(
    double fundamentalFrequencyHz,
    Integer harmonicCount,
    List<Double> harmonics,
    Double modulationFrequencyHz,
    Double modulationDepth,
    Double jitter,
    Double drift,
    Double noiseAmplitude,
    Double signalPower) {

  /* Validate required fields and defensively copy {@link #harmonics()} when provided. */
  public AcousticGroundTruth {
    if (fundamentalFrequencyHz <= 0.0 || !Double.isFinite(fundamentalFrequencyHz)) {
      throw new IllegalArgumentException("fundamentalFrequencyHz must be finite and > 0");
    }
    if (harmonicCount != null && harmonicCount < 1) {
      throw new IllegalArgumentException("harmonicCount must be >= 1 when provided");
    }
    harmonics = harmonics != null ? List.copyOf(harmonics) : null;
    if (harmonics != null) {
      for (int i = 0; i < harmonics.size(); i++) {
        Double harmonic = harmonics.get(i);
        if (harmonic == null || !Double.isFinite(harmonic)) {
          throw new IllegalArgumentException(
              "harmonics[%d] must be non-null and finite".formatted(i));
        }
      }
    }
    if (harmonicCount != null && harmonics != null && harmonicCount != harmonics.size()) {
      throw new IllegalArgumentException(
          "harmonicCount must match harmonics.size() when both are provided");
    }
    if (modulationFrequencyHz != null
        && (!Double.isFinite(modulationFrequencyHz) || modulationFrequencyHz < 0.0)) {
      throw new IllegalArgumentException(
          "modulationFrequencyHz must be finite and >= 0 when provided");
    }
    if (modulationDepth != null
        && (!Double.isFinite(modulationDepth) || modulationDepth < 0.0 || modulationDepth > 1.0)) {
      throw new IllegalArgumentException("modulationDepth must be in [0, 1] when provided");
    }
    if (jitter != null && (!Double.isFinite(jitter) || jitter < 0.0)) {
      throw new IllegalArgumentException("jitter must be finite and >= 0 when provided");
    }
    if (drift != null && !Double.isFinite(drift)) {
      throw new IllegalArgumentException("drift must be finite when provided");
    }
    if (noiseAmplitude != null
        && (!Double.isFinite(noiseAmplitude) || noiseAmplitude < 0.0 || noiseAmplitude > 1.0)) {
      throw new IllegalArgumentException("noiseAmplitude must be in [0, 1] when provided");
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
    return new AcousticGroundTruth(
        fundamentalFrequencyHz, null, null, null, null, null, null, null, null);
  }
}
