package org.hammer.audio.experimental.acoustic.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.hammer.audio.acquisition.Microphone;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.acquisition.MicrophoneArrayCalibration;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.hammer.audio.geometry.Vector2;
import org.junit.jupiter.api.Test;

class ArrayTimingCalibrationEstimatorTest {

  private static final Instant START = Instant.parse("2026-07-21T08:00:00Z");

  @Test
  void recoversKnownInterChannelDelayFromSyntheticPulse() {
    AudioFormatDescriptor format = new AudioFormatDescriptor(48_000.0f, 3, 16);
    AudioBlock block =
        SyntheticCalibrationFixture.pulseBlock(
            format, 2_000, 0, 96, 32, new int[] {0, 4, 9});
    ArrayTimingCalibrationEstimator estimator =
        new ArrayTimingCalibrationEstimator(12, 0.95, 500.0);

    CalibrationEventObservation observation = estimator.observe(block, 0);

    assertEquals(List.of(0.0, 4.0, 9.0), observation.offsetsSamples());
    assertEquals(List.of(1.0, 1.0, 1.0), observation.confidences());
  }

  @Test
  void derivesKnownDriftAcrossRepeatedCalibrationEvents() {
    CalibrationEventObservation first =
        new CalibrationEventObservation(
            1_000, 0, List.of(0.0, 2.0), List.of(1.0, 0.99));
    CalibrationEventObservation second =
        new CalibrationEventObservation(
            101_000, 0, List.of(0.0, 7.0), List.of(1.0, 0.98));
    ArrayTimingCalibrationEstimator estimator =
        new ArrayTimingCalibrationEstimator(16, 0.9, 100.0);

    MicrophoneArrayCalibration calibration =
        estimator.calibrate(
            "drift-profile", array(), first, second, START, START.plusSeconds(3_600));

    assertEquals(50.0, calibration.channel(1).driftPpm(), 1.0e-12);
    assertEquals(7.0, calibration.channel(1).offsetAtFrame(101_000), 1.0e-12);
  }

  @Test
  void rejectsDriftOutsideConfiguredHardwareBound() {
    CalibrationEventObservation first =
        new CalibrationEventObservation(0, 0, List.of(0.0, 0.0), List.of(1.0, 1.0));
    CalibrationEventObservation second =
        new CalibrationEventObservation(10_000, 0, List.of(0.0, 5.0), List.of(1.0, 1.0));
    ArrayTimingCalibrationEstimator estimator =
        new ArrayTimingCalibrationEstimator(16, 0.9, 100.0);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            estimator.calibrate(
                "invalid-drift", array(), first, second, START, START.plusSeconds(60)));
  }

  private static MicrophoneArray array() {
    return new MicrophoneArray(
        List.of(
            new Microphone("reference", new Vector2(-0.05, 0.0), 0),
            new Microphone("second", new Vector2(0.05, 0.0), 1)));
  }
}
