package org.hammer.audio.experimental.acoustic;

import java.time.Clock;
import java.util.Objects;
import org.hammer.audio.acquisition.Microphone;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.acquisition.MicrophoneArrayCalibration;
import org.hammer.audio.acquisition.SynchronizationAssessment;
import org.hammer.audio.core.AudioBlock;

/** Applies validated per-channel offset/drift calibration to another TDOA estimator. */
public final class CalibratedTdoaEstimator implements SynchronizationAwareTdoaEstimator {

  private final TdoaEstimator delegate;
  private final MicrophoneArrayCalibration calibration;
  private final Clock clock;
  private final double maximumErrorSamples;
  private final double speedOfSoundMetersPerSecond;

  /** Creates a calibrated decorator with an explicit timing-error budget. */
  public CalibratedTdoaEstimator(
      TdoaEstimator delegate,
      MicrophoneArrayCalibration calibration,
      Clock clock,
      double maximumErrorSamples,
      double speedOfSoundMetersPerSecond) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.calibration = Objects.requireNonNull(calibration, "calibration");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.maximumErrorSamples = requirePositiveFinite(maximumErrorSamples, "maximumErrorSamples");
    this.speedOfSoundMetersPerSecond =
        requirePositiveFinite(speedOfSoundMetersPerSecond, "speedOfSoundMetersPerSecond");
  }

  @Override
  public TdoaEstimate estimate(
      AudioBlock block, MicrophoneArray array, int firstChannel, int secondChannel) {
    requireCompatibleArray(array);
    SynchronizationAssessment assessment = synchronizationAssessment(block, array);
    if (!assessment.usable()) {
      throw new UnusableSynchronizationException(String.join(" ", assessment.diagnostics()));
    }
    TdoaEstimate raw = delegate.estimate(block, array, firstChannel, secondChannel);
    double hardwareDelaySamples =
        calibration.relativeOffsetSamples(firstChannel, secondChannel, block.frameIndex());
    double rawDelaySamples = raw.delaySeconds() * block.format().sampleRate();
    double correctedDelaySamples = rawDelaySamples - hardwareDelaySamples;
    int roundedDelaySamples = (int) Math.round(correctedDelaySamples);
    double correctedDelaySeconds = correctedDelaySamples / block.format().sampleRate();
    double confidenceScale =
        Math.max(0.0, 1.0 - assessment.estimatedErrorSamples() / maximumErrorSamples);
    return new TdoaEstimate(
        raw.firstMicrophoneId(),
        raw.secondMicrophoneId(),
        roundedDelaySamples,
        correctedDelaySeconds,
        correctedDelaySeconds * speedOfSoundMetersPerSecond,
        raw.confidence() * confidenceScale);
  }

  @Override
  public SynchronizationAssessment synchronizationAssessment(
      AudioBlock block, MicrophoneArray array) {
    Objects.requireNonNull(block, "block");
    requireCompatibleArray(array);
    return calibration.assess(clock.instant(), block.format().sampleRate(), maximumErrorSamples);
  }

  private void requireCompatibleArray(MicrophoneArray array) {
    Objects.requireNonNull(array, "array");
    if (array.channels() != calibration.array().channels()) {
      throw new IllegalArgumentException("microphone array does not match calibration profile");
    }
    for (int channel = 0; channel < array.channels(); channel++) {
      Microphone actual = array.microphone(channel);
      Microphone calibrated = calibration.array().microphone(channel);
      if (!actual.equals(calibrated)) {
        throw new IllegalArgumentException("microphone array does not match calibration profile");
      }
    }
  }

  private static double requirePositiveFinite(double value, String name) {
    if (Double.isFinite(value) && value > 0.0) {
      return value;
    }
    throw new IllegalArgumentException(name + " must be finite and > 0");
  }
}
