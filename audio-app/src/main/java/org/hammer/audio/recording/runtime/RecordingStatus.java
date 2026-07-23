package org.hammer.audio.recording.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Complete immutable runtime snapshot for an experiment recording.
 *
 * @param state current recording lifecycle state
 * @param destination normalized target recording path
 * @param startedAt recording start instant
 * @param updatedAt snapshot observation instant
 * @param receivedBlocks blocks offered by the source subscription
 * @param writtenBlocks blocks successfully serialized
 * @param receivedFrames frames offered by the source subscription
 * @param writtenFrames frames successfully serialized
 * @param droppedBlocks blocks rejected because the recorder queue was full
 * @param droppedFrames frames contained in dropped blocks
 * @param continuityGapCount source frame-index discontinuities detected by the writer
 * @param queueDepth current recorder queue occupancy
 * @param queueCapacity bounded recorder queue capacity
 * @param maximumQueueDepth highest observed queue occupancy
 * @param bytesWritten current serialized bytes including container overhead
 * @param measuredBytesPerSecond observed write throughput
 * @param storage current backing-store capacity snapshot
 * @param stopReason user- or system-visible terminal/degradation reason
 * @param errorMessage terminal error detail, otherwise empty
 */
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

  // Validate required fields and normalize optional text.
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
    Duration elapsed = Duration.between(startedAt, updatedAt);
    return elapsed.isNegative() ? Duration.ZERO : elapsed;
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
