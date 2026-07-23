package org.hammer.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import javax.sound.sampled.AudioFormat;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.hammer.audio.recording.runtime.RecordingStorageLevel;
import org.hammer.audio.recording.runtime.RecordingStorageProbe;
import org.hammer.audio.recording.runtime.RecordingStorageStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingPreflightTest {

  private static final AudioFormatDescriptor FORMAT = new AudioFormatDescriptor(48_000f, 4, 24);
  private static final Instant CHECKED_AT = Instant.parse("2026-07-23T02:00:00Z");

  @Test
  void reportsWarningBeforeCreatingTarget(@TempDir Path directory) throws IOException {
    Path target = directory.resolve("experiment.aarec");
    RecordingStorageProbe probe =
        (destination, bytes, measured, expected, checkedAt) ->
            new RecordingStorageStatus(
                destination,
                "test-store",
                "test",
                true,
                512L * 1024L * 1024L,
                512L * 1024L * 1024L,
                1_024L * 1024L * 1024L,
                bytes,
                measured,
                expected,
                240L,
                RecordingStorageLevel.WARNING,
                checkedAt,
                "");

    RecordingStorageStatus result =
        RecordingPreflight.inspect(new FakeAudioService(FORMAT), target, probe, CHECKED_AT);

    assertEquals(RecordingStorageLevel.WARNING, result.level());
    assertEquals(target, result.destination());
    assertEquals(CHECKED_AT, result.checkedAt());
    assertFalse(Files.exists(target));
    assertFalse(Files.exists(target.resolveSibling("experiment.aarec.partial")));
  }

  @Test
  void rejectsSourceWithoutFormatDescriptor(@TempDir Path directory) {
    RecordingStorageProbe probe =
        (destination, bytes, measured, expected, checkedAt) -> {
          throw new AssertionError("storage probe must not run without a source format");
        };

    assertThrows(
        IOException.class,
        () ->
            RecordingPreflight.inspect(
                new FakeAudioService(null),
                directory.resolve("missing-format.aarec"),
                probe,
                CHECKED_AT));
  }

  private static final class FakeAudioService implements AudioCaptureService {

    private final AudioFormatDescriptor descriptor;

    private FakeAudioService(AudioFormatDescriptor descriptor) {
      this.descriptor = descriptor;
    }

    @Override
    public AudioFormatDescriptor getDescriptor() {
      return descriptor;
    }

    @Override
    public void start() {
      // no resources in preflight tests
    }

    @Override
    public void stop() {
      // no resources in preflight tests
    }

    @Override
    public boolean isRunning() {
      return true;
    }

    @Override
    public WaveformModel getLatestModel() {
      return WaveformModel.EMPTY;
    }

    @Override
    public AudioFormat getFormat() {
      return descriptor == null
          ? null
          : new AudioFormat(
              descriptor.sampleRate(),
              descriptor.sourceSampleSizeInBits(),
              descriptor.channels(),
              true,
              false);
    }

    @Override
    public void setDivisor(int divisor) {
      // irrelevant to preflight
    }

    @Override
    public int getDivisor() {
      return 1;
    }

    @Override
    public void recomputeLayout(int width, int height) {
      // no UI projection
    }
  }
}
