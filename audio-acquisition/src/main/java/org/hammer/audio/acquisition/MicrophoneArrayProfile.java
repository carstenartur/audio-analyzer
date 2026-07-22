package org.hammer.audio.acquisition;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Persistable hardware and geometry profile shared by simulation, replay and live localization.
 *
 * @param profileId stable profile identity
 * @param displayName human-readable profile name
 * @param layout geometry family used for presentation and validation
 * @param array microphone geometry and channel mapping
 * @param supportedModes localization input modes supported by the profile
 * @param liveCapture capture configuration required for live mode, otherwise {@code null}
 * @param calibration current calibration for this exact geometry and channel mapping, otherwise
 *     {@code null}
 */
public record MicrophoneArrayProfile(
    String profileId,
    String displayName,
    MicrophoneArrayLayout layout,
    MicrophoneArray array,
    Set<LocalizationInputMode> supportedModes,
    CaptureDeviceConfiguration liveCapture,
    MicrophoneArrayCalibration calibration) {

  /** Validate one reusable profile and defensively copy its supported modes. */
  public MicrophoneArrayProfile {
    requireText(profileId, "profileId");
    requireText(displayName, "displayName");
    Objects.requireNonNull(layout, "layout");
    Objects.requireNonNull(array, "array");
    Objects.requireNonNull(supportedModes, "supportedModes");
    if (supportedModes.isEmpty()) {
      throw new IllegalArgumentException("supportedModes must not be empty");
    }
    EnumSet<LocalizationInputMode> modes = EnumSet.copyOf(supportedModes);
    supportedModes = Collections.unmodifiableSet(modes);
    if (modes.contains(LocalizationInputMode.LIVE) && liveCapture == null) {
      throw new IllegalArgumentException("liveCapture is required when LIVE mode is supported");
    }
    if (liveCapture != null && liveCapture.format().channels() != array.channels()) {
      throw new IllegalArgumentException(
          "capture channel count must match microphone-array channel count");
    }
    if (calibration != null && !calibration.array().microphones().equals(array.microphones())) {
      throw new IllegalArgumentException(
          "calibration geometry and channel mapping must match the profile array");
    }
  }

  /** Returns whether this profile can be used with one input mode. */
  public boolean supports(LocalizationInputMode mode) {
    return supportedModes.contains(Objects.requireNonNull(mode, "mode"));
  }

  /** Optional live capture configuration. */
  public Optional<CaptureDeviceConfiguration> liveCaptureConfiguration() {
    return Optional.ofNullable(liveCapture);
  }

  /** Optional timing and level calibration. */
  public Optional<MicrophoneArrayCalibration> calibrationProfile() {
    return Optional.ofNullable(calibration);
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
