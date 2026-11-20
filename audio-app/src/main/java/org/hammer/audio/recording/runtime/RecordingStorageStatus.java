package org.hammer.audio.recording.runtime;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * @param policy applied byte/time thresholds and reserve
 * @param lastSuccessfulCheck last successful capacity observation, or null when never successful
 * @param warningHistory significant capacity warnings retained across healthy checks
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
    String errorMessage,
    RecordingStoragePolicy policy,
    Instant lastSuccessfulCheck,
    List<RecordingStorageEvent> warningHistory) {

  // Validate required fields and normalize optional text.
  public RecordingStorageStatus {
    Objects.requireNonNull(destination, "destination");
    storeName = storeName == null ? "" : storeName;
    storeType = storeType == null ? "" : storeType;
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(checkedAt, "checkedAt");
    errorMessage = errorMessage == null ? "" : errorMessage;
    Objects.requireNonNull(policy, "policy");
    warningHistory = List.copyOf(warningHistory);
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
        "Capacity has not been checked yet.",
        RecordingStoragePolicy.configured(),
        null,
        List.of());
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
        errorMessage,
        RecordingStoragePolicy.configured(),
        null,
        List.of());
  }

  /** Returns whether recording should stop before exhausting the backing store. */
  public boolean critical() {
    return level == RecordingStorageLevel.CRITICAL;
  }

  /** Retain warning transitions and the last successful check when a capacity query fails. */
  public RecordingStorageStatus continuing(RecordingStorageStatus previous) {
    List<RecordingStorageEvent> history = new ArrayList<>(previous.warningHistory());
    if (level != RecordingStorageLevel.NORMAL
        && (history.isEmpty()
            || previous.level() != level
            || !previous.errorMessage().equals(errorMessage))) {
      history.add(
          new RecordingStorageEvent(
              level, checkedAt, usableBytes, estimatedSafeSecondsRemaining, errorMessage));
    }
    return new RecordingStorageStatus(
        destination,
        storeName,
        storeType,
        writable,
        usableBytes,
        unallocatedBytes,
        totalBytes,
        bytesWritten,
        measuredBytesPerSecond,
        expectedBytesPerSecond,
        estimatedSafeSecondsRemaining,
        level,
        checkedAt,
        errorMessage,
        previous.policy(),
        level == RecordingStorageLevel.UNKNOWN ? previous.lastSuccessfulCheck() : checkedAt,
        history);
  }
}
