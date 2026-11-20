package org.hammer.audio.recording.runtime;

import java.time.Instant;

/**
 * Significant storage warning retained with the recording evidence.
 *
 * @param level capacity severity
 * @param observedAt observation timestamp
 * @param usableBytes observed usable bytes, or -1 for query failure
 * @param safeSeconds estimated safe remaining duration
 * @param detail explanatory capacity-query diagnostic
 */
public record RecordingStorageEvent(
    RecordingStorageLevel level,
    Instant observedAt,
    long usableBytes,
    long safeSeconds,
    String detail) {
  // Immutable transport value.
}
