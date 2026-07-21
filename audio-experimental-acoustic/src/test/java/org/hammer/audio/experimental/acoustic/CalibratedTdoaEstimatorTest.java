package org.hammer.audio.experimental.acoustic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.hammer.audio.acquisition.ChannelTimingCalibration;
import org.hammer.audio.acquisition.Microphone;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.acquisition.MicrophoneArrayCalibration;
import org.hammer.audio.acquisition.SynchronizationMode;
import org.hammer.audio.acquisition.SynchronizationStatus;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.hammer.audio.geometry.Vector2;
import org.junit.jupiter.api.Test;

class CalibratedTdoaEstimatorTest {

  private static final double SPEED_OF_SOUND = 343.0;
  private static final Instant CALIBRATED_AT = Instant.parse("2026-07-21T08:00:00Z");

  @Test
  void removesPredictedHardwareOffsetFromRawTdoa() {
    MicrophoneArray array = array();
    MicrophoneArrayCalibration calibration = profile(array, CALIBRATED_AT.plusSeconds(3_600), 3.0);
    TdoaEstimator delegate =
        (block, ignoredArray, first, second) ->
            new TdoaEstimate(
                "left", "right", 5, 5.0 / 48_000.0, 5.0 / 48_000.0 * SPEED_OF_SOUND, 1.0);
    CalibratedTdoaEstimator estimator =
        new CalibratedTdoaEstimator(
            delegate,
            calibration,
            Clock.fixed(CALIBRATED_AT.plusSeconds(30), ZoneOffset.UTC),
            0.5,
            SPEED_OF_SOUND);

    TdoaEstimate corrected = estimator.estimate(block(10_000), array, 0, 1);

    assertEquals(2, corrected.delaySamples());
    assertEquals(2.0 / 48_000.0, corrected.delaySeconds(), 1.0e-12);
    assertEquals(
        SynchronizationMode.CALIBRATED_OFFSET,
        estimator.synchronizationAssessment(block(10_000), array).mode());
    assertEquals(
        SynchronizationStatus.TRUSTED,
        estimator.synchronizationAssessment(block(10_000), array).status());
  }

  @Test
  void preservesFractionalCalibrationPrecisionInTimeAndPathDifference() {
    MicrophoneArray array = array();
    MicrophoneArrayCalibration calibration = profile(array, CALIBRATED_AT.plusSeconds(3_600), 2.5);
    TdoaEstimator delegate =
        (block, ignoredArray, first, second) ->
            new TdoaEstimate(
                "left", "right", 5, 5.0 / 48_000.0, 5.0 / 48_000.0 * SPEED_OF_SOUND, 1.0);
    CalibratedTdoaEstimator estimator =
        new CalibratedTdoaEstimator(
            delegate,
            calibration,
            Clock.fixed(CALIBRATED_AT.plusSeconds(30), ZoneOffset.UTC),
            0.5,
            SPEED_OF_SOUND);

    TdoaEstimate corrected = estimator.estimate(block(10_000), array, 0, 1);

    assertEquals(3, corrected.delaySamples());
    assertEquals(2.5 / 48_000.0, corrected.delaySeconds(), 1.0e-12);
    assertEquals(2.5 / 48_000.0 * SPEED_OF_SOUND, corrected.pathDifferenceMeters(), 1.0e-12);
  }

  @Test
  void rejectsExpiredCalibrationBeforeReturningLocalizationEvidence() {
    MicrophoneArray array = array();
    MicrophoneArrayCalibration calibration = profile(array, CALIBRATED_AT.plusSeconds(10), 3.0);
    TdoaEstimator delegate =
        (block, ignoredArray, first, second) ->
            new TdoaEstimate("left", "right", 3, 3.0 / 48_000.0, 0.0, 1.0);
    CalibratedTdoaEstimator estimator =
        new CalibratedTdoaEstimator(
            delegate,
            calibration,
            Clock.fixed(CALIBRATED_AT.plusSeconds(20), ZoneOffset.UTC),
            0.5,
            SPEED_OF_SOUND);

    assertThrows(
        UnusableSynchronizationException.class, () -> estimator.estimate(block(0), array, 0, 1));
  }

  private static MicrophoneArrayCalibration profile(
      MicrophoneArray array, Instant validUntil, double offsetSamples) {
    return new MicrophoneArrayCalibration(
        "two-channel-profile",
        array,
        0,
        List.of(
            new ChannelTimingCalibration(0, 0, 0.0, 0.0, 0.0, 0.0, 1.0, false),
            new ChannelTimingCalibration(1, 0, offsetSamples, 0.0, 0.05, 0.02, 1.0, false)),
        CALIBRATED_AT,
        validUntil);
  }

  private static AudioBlock block(long frameIndex) {
    return AudioBlock.wrap(
        new AudioFormatDescriptor(48_000.0f, 2, 16),
        new float[][] {new float[32], new float[32]},
        frameIndex,
        0);
  }

  private static MicrophoneArray array() {
    return new MicrophoneArray(
        List.of(
            new Microphone("left", new Vector2(-0.05, 0.0), 0),
            new Microphone("right", new Vector2(0.05, 0.0), 1)));
  }
}
