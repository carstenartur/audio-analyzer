package org.hammer.audio.recording.runtime;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** Recording-storage probe backed by {@link FileStore}. */
public final class FileStoreRecordingStorageProbe implements RecordingStorageProbe {

  static final long MINIMUM_WARNING_BYTES = 256L * 1024L * 1024L;
  static final long MINIMUM_CRITICAL_BYTES = 64L * 1024L * 1024L;
  static final long WARNING_SECONDS = 300L;
  static final long CRITICAL_SECONDS = 30L;

  @Override
  public RecordingStorageStatus probe(
      Path destination,
      long bytesWritten,
      double measuredBytesPerSecond,
      double expectedBytesPerSecond,
      Instant checkedAt)
      throws IOException {
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(checkedAt, "checkedAt");
    Path normalized = destination.toAbsolutePath().normalize();
    Path existing = nearestExistingPath(normalized);
    FileStore store = Files.getFileStore(existing);
    long usable = store.getUsableSpace();
    long unallocated = store.getUnallocatedSpace();
    long total = store.getTotalSpace();
    Path writablePath = Files.isDirectory(existing) ? existing : existing.getParent();
    boolean writable = writablePath != null && Files.isWritable(writablePath);
    double effectiveRate =
        measuredBytesPerSecond > 0.0 ? measuredBytesPerSecond : expectedBytesPerSecond;
    long remainingSeconds = effectiveRate > 0.0 ? (long) Math.floor(usable / effectiveRate) : -1L;
    long warningBytes =
        Math.max(MINIMUM_WARNING_BYTES, safeMultiply(expectedBytesPerSecond, WARNING_SECONDS));
    long criticalBytes =
        Math.max(MINIMUM_CRITICAL_BYTES, safeMultiply(expectedBytesPerSecond, CRITICAL_SECONDS));
    RecordingStorageLevel level;
    if (!writable
        || usable <= criticalBytes
        || (remainingSeconds >= 0 && remainingSeconds <= CRITICAL_SECONDS)) {
      level = RecordingStorageLevel.CRITICAL;
    } else if (usable <= warningBytes
        || (remainingSeconds >= 0 && remainingSeconds <= WARNING_SECONDS)) {
      level = RecordingStorageLevel.WARNING;
    } else {
      level = RecordingStorageLevel.NORMAL;
    }
    return new RecordingStorageStatus(
        normalized,
        store.name(),
        store.type(),
        writable,
        usable,
        unallocated,
        total,
        bytesWritten,
        measuredBytesPerSecond,
        expectedBytesPerSecond,
        remainingSeconds,
        level,
        checkedAt,
        "");
  }

  private static Path nearestExistingPath(Path path) throws IOException {
    Path candidate = path;
    while (candidate != null && !Files.exists(candidate)) {
      candidate = candidate.getParent();
    }
    if (candidate == null) {
      throw new IOException("No existing ancestor for recording destination: " + path);
    }
    return candidate;
  }

  private static long safeMultiply(double bytesPerSecond, long seconds) {
    if (bytesPerSecond <= 0.0 || !Double.isFinite(bytesPerSecond)) {
      return 0L;
    }
    double value = bytesPerSecond * seconds;
    return value >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.ceil(value);
  }
}
