package org.hammer.audio.recording.runtime;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable capacity and write-rate snapshot for one recording destination.
 *
 * @param destination normalized target recording path
 * @param storeName backing {@link java.nio.file.FileStore} name when available
 * @param storeType backing store type when available
 * @param writable whether the nearest existing destination ancestor is writable
 * @param usableBytes bytes currently available to this process, or {@code -1}
 * @param unallocatedBytes unallocated backing-store bytes, or {@code -1}
 * @param totalBytes total backing-store bytes, or {@code -1}
 * @param bytesWritten bytes currently serialized by the recorder
 * @param measuredBytesPerSecond observed write throughput
 * @param expectedBytesPerSecond format-derived expected growth rate
 * @param estimatedSafeSecondsRemaining advisory remaining duration, or {@code -1}
 * @param level normalized storage severity
 * @param checkedAt capacity observation time
 * @param errorMessage capacity-query diagnostic, otherwise empty
 */
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

  // Validate required fields and normalize optional text.
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

  /** Snapshot used when a runtime capacity refresh cannot inspect the backing store. */
  public static RecordingStorageStatus unavailable(
      Path destination,
      long bytesWritten,
      double measuredBytesPerSecond,
      double expectedBytesPerSecond,
      Instant now,
      String errorMessage) {
    return new RecordingStorageStatus(
        destination,
        "",
        "",
        false,
        -1L,
        -1L,
        -1L,
        bytesWritten,
        measuredBytesPerSecond,
        expectedBytesPerSecond,
        -1L,
        RecordingStorageLevel.UNKNOWN,
        now,
        errorMessage);
  }

  /** Returns whether recording should stop before exhausting the backing store. */
  public boolean critical() {
    return level == RecordingStorageLevel.CRITICAL;
  }
}
