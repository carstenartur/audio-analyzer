package org.hammer.audio.recording.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Complete immutable runtime snapshot for an experiment recording. */
public record RecordingStatus(
    RecordingState state,
    Path destination,
    Instant startedAt,
    Instant updatedAt,
    long receivedBlocks,
    long writtenBlocks,
    long receivedFrames,
    long writtenFrames,
    long droppedBlocks,
    long droppedFrames,
    long continuityGapCount,
    int queueDepth,
    int queueCapacity,
    int maximumQueueDepth,
    long bytesWritten,
    double measuredBytesPerSecond,
    RecordingStorageStatus storage,
    String stopReason,
    String errorMessage) {

  public RecordingStatus {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(startedAt, "startedAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    Objects.requireNonNull(storage, "storage");
    stopReason = stopReason == null ? "" : stopReason;
    errorMessage = errorMessage == null ? "" : errorMessage;
  }

  /** Elapsed wall-clock recording time. */
  public Duration elapsed() {
    return Duration.between(startedAt, updatedAt).isNegative()
        ? Duration.ZERO
        : Duration.between(startedAt, updatedAt);
  }

  /** Returns whether all received blocks were written without a continuity gap. */
  public boolean completeSoFar() {
    return droppedBlocks == 0L && continuityGapCount == 0L && state != RecordingState.FAILED;
  }

  /** Returns whether this is a terminal status. */
  public boolean terminal() {
    return state == RecordingState.COMPLETED
        || state == RecordingState.INCOMPLETE
        || state == RecordingState.FAILED;
  }
}
