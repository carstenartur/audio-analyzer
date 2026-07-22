package org.hammer.audio.acquisition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.hammer.audio.geometry.Vector2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MicrophoneArrayProfileWorkflowTest {

  private static final Instant CALIBRATED_AT = Instant.parse("2026-07-22T06:00:00Z");
  private static final Instant VALID_UNTIL = Instant.parse("2026-08-22T06:00:00Z");

  @TempDir Path temporaryDirectory;

  @Test
  void profileCodecRoundTripsDeterministicallyWithHardwareAndCalibration() {
    MicrophoneArrayProfileCodec codec = new MicrophoneArrayProfileCodec();
    String encoded = codec.encode(profile(true));

    MicrophoneArrayProfile decoded = codec.decode(encoded);

    assertEquals(encoded, codec.encode(decoded));
    assertEquals("desk-array", decoded.profileId());
    assertEquals(2, decoded.array().channels());
    assertTrue(decoded.supports(LocalizationInputMode.SIMULATION));
    assertTrue(decoded.supports(LocalizationInputMode.REPLAY));
    assertTrue(decoded.supports(LocalizationInputMode.LIVE));
    assertEquals("java-sound:test-device", decoded.liveCapture().device().deviceId());
    assertEquals(1.25, decoded.calibration().channel(1).offsetSamples(), 1.0e-12);
  }

  @Test
  void readinessRejectsUncalibratedLiveUseAndAcceptsOtherModes() {
    MicrophoneArrayProfile uncalibrated = profile(false);

    MicrophoneArrayReadiness live =
        MicrophoneArrayReadiness.assess(
            uncalibrated, LocalizationInputMode.LIVE, CALIBRATED_AT.plusSeconds(60L), 0.5);
    MicrophoneArrayReadiness replay =
        MicrophoneArrayReadiness.assess(
            uncalibrated, LocalizationInputMode.REPLAY, CALIBRATED_AT.plusSeconds(60L), 0.5);

    assertFalse(live.ready());
    assertTrue(live.diagnostics().stream().anyMatch(message -> message.contains("calibration")));
    assertTrue(replay.ready());
  }

  @Test
  void directoryStoreCreatesUpdatesListsLoadsAndDeletesProfiles() throws Exception {
    DirectoryMicrophoneArrayProfileStore store =
        new DirectoryMicrophoneArrayProfileStore(temporaryDirectory.resolve("profiles"));
    MicrophoneArrayProfile first = profile(true);
    MicrophoneArrayProfile second =
        new MicrophoneArrayProfile(
            "another-array",
            "Another array",
            first.layout(),
            first.array(),
            EnumSet.of(LocalizationInputMode.REPLAY),
            null,
            first.calibration());

    store.save(first);
    store.save(second);

    assertEquals(List.of("another-array", "desk-array"), store.list().stream().map(MicrophoneArrayProfile::profileId).toList());
    assertEquals("Desk array", store.find("desk-array").orElseThrow().displayName());
    assertTrue(store.delete("desk-array"));
    assertFalse(store.find("desk-array").isPresent());
  }

  @Test
  void experimentManifestPreservesProfileLifecycleAndOrderedMetadata() {
    LocalizationExperiment experiment =
        LocalizationExperiment.defined(
                "experiment-1",
                "Desk array validation",
                profile(true),
                LocalizationInputMode.LIVE,
                "java-sound:test-device",
                CALIBRATED_AT.plusSeconds(60L))
            .advanceTo(LocalizationExperimentStage.CALIBRATED)
            .advanceTo(LocalizationExperimentStage.RECORDED)
            .advanceTo(LocalizationExperimentStage.LOCALIZED)
            .withMetadata("operator", "test-user")
            .withMetadata("recording.sha256", "abc123");
    LocalizationExperimentCodec codec = new LocalizationExperimentCodec();

    String encoded = codec.encode(experiment);
    LocalizationExperiment decoded = codec.decode(encoded);

    assertEquals(encoded, codec.encode(decoded));
    assertEquals(LocalizationExperimentStage.LOCALIZED, decoded.stage());
    assertEquals("abc123", decoded.metadata().get("recording.sha256"));
    assertEquals("desk-array", decoded.profile().profileId());
  }

  private static MicrophoneArrayProfile profile(boolean calibrated) {
    MicrophoneArray array =
        new MicrophoneArray(
            List.of(
                new Microphone("left", new Vector2(-0.1, 0.0), 0),
                new Microphone("right", new Vector2(0.1, 0.0), 1)));
    CaptureDeviceConfiguration capture =
        new CaptureDeviceConfiguration(
            new CaptureDeviceDescriptor(
                "java-sound:test-device",
                "Test device",
                "Test vendor",
                "Deterministic test input",
                "1"),
            new AudioFormatDescriptor(48_000.0f, 2, 16),
            true,
            false);
    MicrophoneArrayCalibration calibration =
        calibrated
            ? new MicrophoneArrayCalibration(
                "desk-array-calibration",
                array,
                0,
                List.of(
                    new ChannelTimingCalibration(0, 0L, 0.0, 0.0, 0.05, 0.02, 1.0, false),
                    new ChannelTimingCalibration(1, 0L, 1.25, 0.1, 0.06, 0.03, 0.98, false)),
                CALIBRATED_AT,
                VALID_UNTIL)
            : null;
    return new MicrophoneArrayProfile(
        "desk-array",
        "Desk array",
        MicrophoneArrayLayout.STEREO_PAIR,
        array,
        EnumSet.allOf(LocalizationInputMode.class),
        capture,
        calibration);
  }
}
