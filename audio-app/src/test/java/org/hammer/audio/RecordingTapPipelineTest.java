package org.hammer.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.hammer.audio.recording.AudioBlockRecordingReader;
import org.hammer.audio.recording.RecordingIntegrity;
import org.hammer.audio.recording.runtime.RecordingState;
import org.hammer.audio.recording.runtime.RecordingStorageLevel;
import org.hammer.audio.recording.runtime.RecordingStorageProbe;
import org.hammer.audio.recording.runtime.RecordingStorageStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingTapPipelineTest {

  private static final AudioFormatDescriptor FORMAT = new AudioFormatDescriptor(48_000f, 2, 16);
  private static final Instant START = Instant.parse("2026-07-22T12:00:00Z");

  @Test
  void recordsEveryRapidlyPublishedBlockWithoutUsingUiPolling(@TempDir Path directory)
      throws IOException {
    FakeAudioService service = new FakeAudioService();
    Path file = directory.resolve("all-blocks.aarec");
    RecordingTap tap = RecordingTap.start(service, file, 128, healthyProbe(), START);

    for (int index = 0; index < 100; index++) {
      service.emit(block(index * 8L, index));
    }
    tap.stop();

    assertEquals(RecordingState.COMPLETED, tap.status().state());
    assertEquals(100L, tap.status().receivedBlocks());
    assertEquals(100L, tap.status().writtenBlocks());
    assertEquals(800L, tap.status().receivedFrames());
    assertEquals(800L, tap.status().writtenFrames());
    assertEquals(0L, tap.status().droppedBlocks());
    assertEquals(0L, tap.status().continuityGapCount());
    assertEquals(100, AudioBlockRecordingReader.readAll(file).size());
    assertEquals(
        RecordingIntegrity.COMPLETE, AudioBlockRecordingReader.inspect(file).integrity());
  }

  @Test
  void criticalCapacityStopsAndFinalizesAsIncomplete(@TempDir Path directory) throws Exception {
    FakeAudioService service = new FakeAudioService();
    AtomicBoolean first = new AtomicBoolean(true);
    RecordingStorageProbe probe =
        (destination, bytes, measured, expected, checkedAt) ->
            storage(
                destination,
                first.getAndSet(false)
                    ? RecordingStorageLevel.NORMAL
                    : RecordingStorageLevel.CRITICAL,
                bytes,
                checkedAt);
    RecordingTap tap =
        RecordingTap.start(service, directory.resolve("low-space.aarec"), 16, probe, START);

    service.emit(block(0L, 1L));
    waitUntilClosed(tap);

    assertEquals(RecordingState.INCOMPLETE, tap.status().state());
    assertTrue(tap.status().stopReason().contains("filesystem"));
    assertEquals(1L, tap.status().writtenBlocks());
  }

  @Test
  void preflightRejectsUnwritableDestinationBeforeCreatingFile(@TempDir Path directory) {
    FakeAudioService service = new FakeAudioService();
    Path file = directory.resolve("unwritable.aarec");
    RecordingStorageProbe probe =
        (destination, bytes, measured, expected, checkedAt) ->
            new RecordingStorageStatus(
                destination,
                "test",
                "test",
                false,
                1_000_000_000L,
                1_000_000_000L,
                1_000_000_000L,
                bytes,
                measured,
                expected,
                1000L,
                RecordingStorageLevel.CRITICAL,
                checkedAt,
                "read-only");

    assertThrows(IOException.class, () -> RecordingTap.start(service, file, 16, probe, START));
    assertFalse(java.nio.file.Files.exists(file));
    assertFalse(java.nio.file.Files.exists(file.resolveSibling("unwritable.aarec.partial")));
  }

  private static void waitUntilClosed(RecordingTap tap) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
    while (!tap.isClosed() && System.nanoTime() < deadline) {
      Thread.sleep(10L);
    }
    assertTrue(tap.isClosed(), "recording did not reach a terminal state");
  }

  private static RecordingStorageProbe healthyProbe() {
    return (destination, bytes, measured, expected, checkedAt) ->
        storage(destination, RecordingStorageLevel.NORMAL, bytes, checkedAt);
  }

  private static RecordingStorageStatus storage(
      Path destination, RecordingStorageLevel level, long bytes, Instant checkedAt) {
    return new RecordingStorageStatus(
        destination,
        "test-store",
        "test",
        true,
        10_000_000_000L,
        10_000_000_000L,
        20_000_000_000L,
        bytes,
        1_000_000.0,
        1_000_000.0,
        10_000L,
        level,
        checkedAt,
        "");
  }

  private static AudioBlock block(long frameIndex, long timestamp) {
    float[][] samples = new float[2][8];
    for (int frame = 0; frame < 8; frame++) {
      samples[0][frame] = frame / 8.0f;
      samples[1][frame] = -samples[0][frame];
    }
    return new AudioBlock(FORMAT, samples, frameIndex, timestamp);
  }

  private static final class FakeAudioService implements AudioCaptureService {

    private volatile AudioBlockListener listener;

    void emit(AudioBlock block) {
      AudioBlockListener current = listener;
      if (current != null) {
        current.onAudioBlock(block);
      }
    }

    @Override
    public AudioBlockSubscription subscribe(AudioBlockListener newListener) {
      listener = newListener;
      AtomicBoolean closed = new AtomicBoolean(false);
      return new AudioBlockSubscription() {
        @Override
        public void close() {
          if (closed.compareAndSet(false, true)) {
            listener = null;
          }
        }

        @Override
        public boolean isClosed() {
          return closed.get();
        }
      };
    }

    @Override
    public AudioFormatDescriptor getDescriptor() {
      return FORMAT;
    }

    @Override
    public void start() {
      // deterministic test source is push-driven
    }

    @Override
    public void stop() {
      // deterministic test source owns no resources
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
      return new AudioFormat(48_000f, 16, 2, true, false);
    }

    @Override
    public void setDivisor(int divisor) {
      // not relevant to this push-driven test source
    }

    @Override
    public int getDivisor() {
      return 1;
    }

    @Override
    public void recomputeLayout(int width, int height) {
      // test source has no UI projection
    }
  }
}
