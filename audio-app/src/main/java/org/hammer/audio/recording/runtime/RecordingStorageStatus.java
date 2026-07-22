package org.hammer.audio.recording.runtime;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** Immutable capacity and write-rate snapshot for one recording destination. */
public record RecordingStorageStatus(
    Path destination,
    String storeName,
    String storeType,
    boolean writable,
    long usableBytes,
    long unallocatedBytes,
    long totalBytes,
    long bytesWritten,
    double measuredBytesPerSecond,
    double expectedBytesPerSecond,
    long estimatedSafeSecondsRemaining,
    RecordingStorageLevel level,
    Instant checkedAt,
    String errorMessage) {

  public RecordingStorageStatus {
    Objects.requireNonNull(destination, "destination");
    storeName = storeName == null ? "" : storeName;
    storeType = storeType == null ? "" : storeType;
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(checkedAt, "checkedAt");
    errorMessage = errorMessage == null ? "" : errorMessage;
  }

  /** Unknown snapshot used before the first asynchronous capacity probe completes. */
  public static RecordingStorageStatus unknown(Path destination, long bytesWritten, Instant now) {
    return new RecordingStorageStatus(
        destination,
        "",
        "",
        false,
        -1L,
        -1L,
        -1L,
        bytesWritten,
        0.0,
        0.0,
        -1L,
        RecordingStorageLevel.UNKNOWN,
        now,
        "Capacity has not been checked yet.");
  }

  /** Returns whether recording should stop before exhausting the backing store. */
  public boolean critical() {
    return level == RecordingStorageLevel.CRITICAL;
  }
}
