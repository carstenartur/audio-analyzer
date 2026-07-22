package org.hammer.audio.acquisition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic readiness result for starting localization with one profile and source mode.
 *
 * @param ready whether localization may start
 * @param synchronization synchronization evidence used by the decision
 * @param diagnostics ordered human-readable findings
 */
public record MicrophoneArrayReadiness(
    boolean ready, SynchronizationAssessment synchronization, List<String> diagnostics) {

  // Validate and defensively copy one readiness result.
  public MicrophoneArrayReadiness {
    Objects.requireNonNull(synchronization, "synchronization");
    diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
  }

  /** Evaluate a profile before simulation, replay or live localization starts. */
  public static MicrophoneArrayReadiness assess(
      MicrophoneArrayProfile profile,
      LocalizationInputMode mode,
      Instant observationTime,
      double maximumTimingErrorSamples) {
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(observationTime, "observationTime");
    if (!Double.isFinite(maximumTimingErrorSamples) || maximumTimingErrorSamples <= 0.0) {
      throw new IllegalArgumentException("maximumTimingErrorSamples must be finite and > 0");
    }

    List<String> diagnostics = new ArrayList<>();
    if (!profile.supports(mode)) {
      diagnostics.add("Profile does not support input mode " + mode + '.');
      return new MicrophoneArrayReadiness(
          false, SynchronizationAssessment.nominalSharedClock(), diagnostics);
    }

    if (mode != LocalizationInputMode.LIVE) {
      diagnostics.add("Non-live source uses deterministic shared-clock timing evidence.");
      return new MicrophoneArrayReadiness(
          true, SynchronizationAssessment.nominalSharedClock(), diagnostics);
    }

    CaptureDeviceConfiguration capture = profile.liveCaptureConfiguration().orElseThrow();
    if (capture.format().channels() != profile.array().channels()) {
      diagnostics.add("Capture channel count does not match the microphone-array profile.");
      return new MicrophoneArrayReadiness(
          false, SynchronizationAssessment.nominalSharedClock(), diagnostics);
    }
    if (profile.calibration() == null) {
      diagnostics.add("Live localization requires a calibration for this array mapping.");
      return new MicrophoneArrayReadiness(
          false, SynchronizationAssessment.nominalSharedClock(), diagnostics);
    }

    SynchronizationAssessment synchronization =
        profile
            .calibration()
            .assess(observationTime, capture.format().sampleRate(), maximumTimingErrorSamples);
    diagnostics.addAll(synchronization.diagnostics());
    return new MicrophoneArrayReadiness(synchronization.usable(), synchronization, diagnostics);
  }
}
