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

  /** Creates a complete validated calibration profile. */
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
    if (!(sampleRate > 0.0f) || !Float.isFinite(sampleRate)) {
      throw new IllegalArgumentException("sampleRate must be finite and > 0");
    }
    if (!(maximumErrorSamples > 0.0) || !Double.isFinite(maximumErrorSamples)) {
      throw new IllegalArgumentException("maximumErrorSamples must be finite and > 0");
    }
    boolean current =
        !observationTime.isBefore(calibratedAt) && !observationTime.isAfter(validUntil);
    double estimatedErrorSamples =
        channels.stream()
            .mapToDouble(ChannelTimingCalibration::timingUncertaintySamples)
            .max()
            .orElse(0.0);
    SynchronizationStatus status;
    List<String> diagnostics = new ArrayList<>();
    if (!current) {
      status = SynchronizationStatus.REJECTED;
      diagnostics.add("Calibration is outside its validity window.");
    } else if (estimatedErrorSamples > maximumErrorSamples) {
      status = SynchronizationStatus.REJECTED;
      diagnostics.add("Estimated timing error exceeds the localization error budget.");
    } else if (estimatedErrorSamples > maximumErrorSamples * 0.5) {
      status = SynchronizationStatus.DEGRADED;
      diagnostics.add("Estimated timing error consumes more than half of the error budget.");
    } else {
      status = SynchronizationStatus.TRUSTED;
      diagnostics.add("Calibration is current and inside the timing error budget.");
    }
    diagnostics.add("Calibration profile: " + profileId);
    return new SynchronizationAssessment(
        mode(),
        status,
        estimatedErrorSamples,
        estimatedErrorSamples / sampleRate,
        current,
        diagnostics);
  }
}
