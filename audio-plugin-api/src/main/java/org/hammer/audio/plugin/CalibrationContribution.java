package org.hammer.audio.plugin;

/**
 * Describes a calibration contribution supplied by a plugin.
 *
 * <p>A calibration contribution represents a procedure or persistent state that adjusts a model or
 * generator to match observed real-world data (e.g. tuning synthetic-signal parameters from a
 * real-recording corpus, compensating microphone sensitivities, or normalizing feature
 * distributions). Plugins expose calibration contributions through this interface so the host can
 * enumerate, persist and replay them without depending on the concrete calibration domain.
 *
 * <p>This is a pure metadata/identity interface.
 */
public interface CalibrationContribution {

  /** Stable identifier for this calibration procedure or state. */
  String id();

  /** Human-readable name shown in calibration management UI. */
  String name();

  /** Single-sentence description of what this calibration adjusts. */
  String description();

  /**
   * Broad category of calibration (e.g. {@code "generator"}, {@code "microphone"}, {@code
   * "feature-normalization"}, {@code "latency"}).
   */
  String category();
}
