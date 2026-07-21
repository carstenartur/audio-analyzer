package org.hammer.audio.acquisition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.hammer.audio.geometry.Vector2;
import org.junit.jupiter.api.Test;

class MicrophoneArrayCalibrationTest {

  private static final Instant CALIBRATED_AT = Instant.parse("2026-07-21T08:00:00Z");
  private static final Instant VALID_UNTIL = Instant.parse("2026-07-22T08:00:00Z");

  @Test
  void predictsAffineOffsetAndRelativeChannelCorrection() {
    ChannelTimingCalibration drifting =
        new ChannelTimingCalibration(1, 1_000, 2.5, 100.0, 0.05, 0.02, 1.0, false);
    assertEquals(2.6, drifting.offsetAtFrame(2_000), 1.0e-12);

    MicrophoneArrayCalibration profile =
        new MicrophoneArrayCalibration(
            "array.calibration",
            array(),
            0,
            List.of(reference(), drifting),
            CALIBRATED_AT,
            VALID_UNTIL);

    assertEquals(2.6, profile.relativeOffsetSamples(0, 1, 2_000), 1.0e-12);
    assertEquals(-2.6, profile.relativeOffsetSamples(1, 0, 2_000), 1.0e-12);
    assertEquals(SynchronizationMode.DRIFT_COMPENSATED, profile.mode());
  }

  @Test
  void assessesCurrentDegradedAndRejectedProfilesAgainstErrorBudget() {
    MicrophoneArrayCalibration trusted =
        profile(new ChannelTimingCalibration(1, 0, 3.0, 0.0, 0.1, 0.1, 1.0, false));
    SynchronizationAssessment trustedAssessment =
        trusted.assess(CALIBRATED_AT.plusSeconds(60), 48_000.0f, 0.5);
    assertEquals(SynchronizationStatus.TRUSTED, trustedAssessment.status());
    assertTrue(trustedAssessment.usable());

    MicrophoneArrayCalibration degraded =
        profile(new ChannelTimingCalibration(1, 0, 3.0, 0.0, 0.3, 0.1, 1.0, false));
    assertEquals(
        SynchronizationStatus.DEGRADED,
        degraded.assess(CALIBRATED_AT.plusSeconds(60), 48_000.0f, 0.5).status());

    SynchronizationAssessment expired =
        trusted.assess(VALID_UNTIL.plusSeconds(1), 48_000.0f, 0.5);
    assertEquals(SynchronizationStatus.REJECTED, expired.status());
    assertTrue(expired.diagnostics().getFirst().contains("validity window"));
  }

  @Test
  void rejectsIncompleteOrNonZeroReferenceCalibration() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MicrophoneArrayCalibration(
                "incomplete", array(), 0, List.of(reference()), CALIBRATED_AT, VALID_UNTIL));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MicrophoneArrayCalibration(
                "bad-reference",
                array(),
                0,
                List.of(
                    new ChannelTimingCalibration(0, 0, 1.0, 0.0, 0.0, 0.0, 1.0, false),
                    new ChannelTimingCalibration(1, 0, 0.0, 0.0, 0.0, 0.0, 1.0, false)),
                CALIBRATED_AT,
                VALID_UNTIL));
  }

  private static MicrophoneArrayCalibration profile(ChannelTimingCalibration channel) {
    return new MicrophoneArrayCalibration(
        "array.calibration", array(), 0, List.of(reference(), channel), CALIBRATED_AT, VALID_UNTIL);
  }

  private static ChannelTimingCalibration reference() {
    return new ChannelTimingCalibration(0, 0, 0.0, 0.0, 0.0, 0.0, 1.0, false);
  }

  private static MicrophoneArray array() {
    return new MicrophoneArray(
        List.of(
            new Microphone("left", new Vector2(-0.05, 0.0), 0),
            new Microphone("right", new Vector2(0.05, 0.0), 1)));
  }
}
