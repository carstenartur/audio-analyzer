package org.hammer.audio.recording.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileStoreRecordingStorageProbeTest {

  @Test
  void resolvesExistingParentAndReportsReadOnlyStore(@TempDir Path directory) throws IOException {
    AtomicReference<Path> probed = new AtomicReference<>();
    var policy = new RecordingStoragePolicy(500, 100, 5, 1, 0.1);
    var probe =
        new FileStoreRecordingStorageProbe(
            policy,
            path -> {
              probed.set(path);
              return new TestStore(true, 10000);
            });
    var status =
        probe.probe(directory.resolve("missing/folder/capture.aarec"), 0, 0, 100, Instant.EPOCH);
    assertEquals(directory, probed.get());
    assertFalse(status.writable());
    assertEquals(RecordingStorageLevel.CRITICAL, status.level());
    assertEquals(Instant.EPOCH, status.lastSuccessfulCheck());
  }

  @Test
  void queryFailuresRetainPreviousSuccessfulObservationAndWarningHistory(@TempDir Path directory)
      throws IOException {
    var policy = new RecordingStoragePolicy(500, 100, 5, 1, 0.1);
    var probe = new FileStoreRecordingStorageProbe(policy, path -> new TestStore(false, 400));
    var warning =
        probe
            .probe(directory.resolve("capture.aarec"), 0, 0, 100, Instant.EPOCH)
            .continuing(
                RecordingStorageStatus.unknown(
                    directory.resolve("capture.aarec"), 0L, Instant.EPOCH));
    assertEquals(RecordingStorageLevel.WARNING, warning.level());
    assertEquals(1, warning.warningHistory().size());
    var failed =
        RecordingStorageStatus.unavailable(
                warning.destination(),
                10,
                100,
                100,
                Instant.EPOCH.plusSeconds(2),
                "Quota lookup failed")
            .continuing(warning);
    assertEquals(RecordingStorageLevel.UNKNOWN, failed.level());
    assertEquals(Instant.EPOCH, failed.lastSuccessfulCheck());
    assertEquals(2, failed.warningHistory().size());
    assertTrue(failed.errorMessage().contains("Quota"));
    var failing =
        new FileStoreRecordingStorageProbe(
            policy,
            path -> {
              throw new IOException("removed volume");
            });
    assertThrows(
        IOException.class, () -> failing.probe(warning.destination(), 0, 0, 100, Instant.EPOCH));
  }

  private static final class TestStore extends FileStore {
    private final boolean readOnly;
    private final long usable;

    private TestStore(boolean readOnly, long usable) {
      this.readOnly = readOnly;
      this.usable = usable;
    }

    @Override
    public String name() {
      return "test-volume";
    }

    @Override
    public String type() {
      return "deterministic";
    }

    @Override
    public boolean isReadOnly() {
      return readOnly;
    }

    @Override
    public long getTotalSpace() {
      return 10000;
    }

    @Override
    public long getUsableSpace() {
      return usable;
    }

    @Override
    public long getUnallocatedSpace() {
      return usable;
    }

    @Override
    public boolean supportsFileAttributeView(Class<? extends FileAttributeView> type) {
      return false;
    }

    @Override
    public boolean supportsFileAttributeView(String name) {
      return false;
    }

    @Override
    public <V extends FileStoreAttributeView> V getFileStoreAttributeView(Class<V> type) {
      return null;
    }

    @Override
    public Object getAttribute(String attribute) {
      throw new UnsupportedOperationException(attribute);
    }
  }
}
