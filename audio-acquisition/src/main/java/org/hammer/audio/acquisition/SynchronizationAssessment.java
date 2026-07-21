package org.hammer.audio.acquisition;

import java.util.List;
import java.util.Objects;

/**
 * Immutable synchronization quality attached to one localization observation.
 *
 * @param mode synchronization model used by the estimator
 * @param status trust decision against the configured timing-error budget
 * @param estimatedErrorSamples conservative timing error in samples
 * @param estimatedErrorSeconds conservative timing error in seconds
 * @param calibrationCurrent whether the calibration validity window contains the observation time
 * @param diagnostics human-readable deterministic diagnostics
 */
public record SynchronizationAssessment(
    SynchronizationMode mode,
    SynchronizationStatus status,
    double estimatedErrorSamples,
    double estimatedErrorSeconds,
    boolean calibrationCurrent,
    List<String> diagnostics) {

  // Validate and defensively copy one synchronization assessment.
  public SynchronizationAssessment {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(status, "status");
    requireNonNegativeFinite(estimatedErrorSamples, "estimatedErrorSamples");
    requireNonNegativeFinite(estimatedErrorSeconds, "estimatedErrorSeconds");
    diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
  }

  /** Declares a shared-clock observation with no measured inter-channel calibration error. */
  public static SynchronizationAssessment nominalSharedClock() {
    return new SynchronizationAssessment(
        SynchronizationMode.NOMINAL_SHARED_CLOCK,
        SynchronizationStatus.TRUSTED,
        0.0,
        0.0,
        true,
        List.of("Channels are assumed to share one hardware sample clock."));
  }

  /** Returns whether localization should proceed with this synchronization state. */
  public boolean usable() {
    return status != SynchronizationStatus.REJECTED;
  }

  private static void requireNonNegativeFinite(double value, String name) {
    if (Double.isFinite(value) && value >= 0.0) {
      return;
    }
    throw new IllegalArgumentException(name + " must be finite and >= 0");
  }
}
