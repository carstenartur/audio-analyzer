package org.hammer.audio.acquisition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Immutable timing/level calibration profile for one microphone-array channel mapping.
 *
 * @param profileId stable profile identity
 * @param array microphone geometry and channel mapping covered by the profile
 * @param referenceChannel channel used as the zero-delay timing anchor
 * @param channels per-channel calibration ordered by channel
 * @param calibratedAt wall-clock time of the calibration procedure
 * @param validUntil last wall-clock instant at which the profile may be trusted
 */
public record MicrophoneArrayCalibration(
    String profileId,
    MicrophoneArray array,
    int referenceChannel,
    List<ChannelTimingCalibration> channels,
    Instant calibratedAt,
    Instant validUntil) {

  private static final double DRIFT_EPSILON_PPM = 1.0e-9;

  // Validate one complete calibration profile.
  public MicrophoneArrayCalibration {
    if (profileId == null || profileId.isBlank()) {
      throw new IllegalArgumentException("profileId must not be blank");
    }
    Objects.requireNonNull(array, "array");
    Objects.requireNonNull(calibratedAt, "calibratedAt");
    Objects.requireNonNull(validUntil, "validUntil");
    if (validUntil.isBefore(calibratedAt)) {
      throw new IllegalArgumentException("validUntil must not be before calibratedAt");
    }
    if (referenceChannel < 0 || referenceChannel >= array.channels()) {
      throw new IllegalArgumentException("referenceChannel must exist in microphone array");
    }
    List<ChannelTimingCalibration> sorted =
        new ArrayList<>(Objects.requireNonNull(channels, "channels"));
    sorted.sort(Comparator.comparingInt(ChannelTimingCalibration::channel));
    if (sorted.size() != array.channels()) {
      throw new IllegalArgumentException("calibration must cover every microphone-array channel");
    }
    for (int channel = 0; channel < sorted.size(); channel++) {
      if (sorted.get(channel).channel() != channel) {
        throw new IllegalArgumentException("calibration channels must be contiguous from 0");
      }
    }
    ChannelTimingCalibration reference = sorted.get(referenceChannel);
    if (Math.abs(reference.offsetSamples()) > 1.0e-9
        || Math.abs(reference.driftPpm()) > DRIFT_EPSILON_PPM) {
      throw new IllegalArgumentException("reference channel must have zero offset and drift");
    }
    channels = List.copyOf(sorted);
  }

  /** Returns calibration for one channel. */
  public ChannelTimingCalibration channel(int channel) {
    return channels.get(channel);
  }

  /** Returns the predicted second-minus-first hardware delay at an absolute nominal frame. */
  public double relativeOffsetSamples(int firstChannel, int secondChannel, long frameIndex) {
    return channel(secondChannel).offsetAtFrame(frameIndex)
        - channel(firstChannel).offsetAtFrame(frameIndex);
  }

  /** Returns whether at least one non-reference channel has measurable drift compensation. */
  public SynchronizationMode mode() {
    boolean driftCompensated =
        channels.stream().anyMatch(channel -> Math.abs(channel.driftPpm()) > DRIFT_EPSILON_PPM);
    return driftCompensated
        ? SynchronizationMode.DRIFT_COMPENSATED
        : SynchronizationMode.CALIBRATED_OFFSET;
  }

  /**
   * Assesses this profile for one observation and a caller-defined one-sigma timing-error budget.
   */
  public SynchronizationAssessment assess(
      Instant observationTime, float sampleRate, double maximumErrorSamples) {
    Objects.requireNonNull(observationTime, "observationTime");
    requirePositiveFinite(sampleRate, "sampleRate");
    requirePositiveFinite(maximumErrorSamples, "maximumErrorSamples");

    boolean outsideValidityWindow =
        observationTime.isBefore(calibratedAt) || observationTime.isAfter(validUntil);
    boolean current = !outsideValidityWindow;
    double estimatedErrorSamples =
        channels.stream()
            .mapToDouble(ChannelTimingCalibration::timingUncertaintySamples)
            .max()
            .orElse(0.0);
    List<String> diagnostics = new ArrayList<>();
    SynchronizationStatus status =
        assessStatus(outsideValidityWindow, estimatedErrorSamples, maximumErrorSamples, diagnostics);
    diagnostics.add("Calibration profile: " + profileId);
    return new SynchronizationAssessment(
        mode(),
        status,
        estimatedErrorSamples,
        estimatedErrorSamples / sampleRate,
        current,
        diagnostics);
  }

  private static SynchronizationStatus assessStatus(
      boolean outsideValidityWindow,
      double estimatedErrorSamples,
      double maximumErrorSamples,
      List<String> diagnostics) {
    if (outsideValidityWindow) {
      diagnostics.add("Calibration is outside its validity window.");
      return SynchronizationStatus.REJECTED;
    }
    if (estimatedErrorSamples > maximumErrorSamples) {
      diagnostics.add("Estimated timing error exceeds the localization error budget.");
      return SynchronizationStatus.REJECTED;
    }
    if (estimatedErrorSamples > maximumErrorSamples * 0.5) {
      diagnostics.add("Estimated timing error consumes more than half of the error budget.");
      return SynchronizationStatus.DEGRADED;
    }
    diagnostics.add("Calibration is current and inside the timing error budget.");
    return SynchronizationStatus.TRUSTED;
  }

  private static void requirePositiveFinite(double value, String name) {
    if (Double.isFinite(value) && value > 0.0) {
      return;
    }
    throw new IllegalArgumentException(name + " must be finite and > 0");
  }
}
