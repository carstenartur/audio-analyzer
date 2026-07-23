package org.hammer.audio.recording;

import java.util.Objects;
import org.hammer.audio.core.AudioFormatDescriptor;

/**
 * Immutable structural and integrity summary for one recording.
 *
 * @param formatVersion parsed binary format version
 * @param format normalized audio format from the recording header
 * @param integrity structural, finalization and checksum classification
 * @param blockCount number of complete readable block records
 * @param totalFrames total frames in complete readable blocks
 * @param firstFrameIndex first complete block's frame index, or {@link Long#MIN_VALUE}
 * @param lastFrameIndex last complete block's frame index, or {@link Long#MIN_VALUE}
 * @param firstTimestampNanos first complete block's source timestamp, or {@link Long#MIN_VALUE}
 * @param lastTimestampNanos last complete block's source timestamp, or {@link Long#MIN_VALUE}
 * @param continuityGapCount detected discontinuities between complete source blocks
 * @param payloadBytes serialized bytes occupied by complete block records
 * @param sha256 verified lowercase SHA-256 for a complete version-2 file, otherwise empty
 * @param detail human-readable integrity explanation
 */
public record RecordingInspection(
    int formatVersion,
    AudioFormatDescriptor format,
    RecordingIntegrity integrity,
    long blockCount,
    long totalFrames,
    long firstFrameIndex,
    long lastFrameIndex,
    long firstTimestampNanos,
    long lastTimestampNanos,
    long continuityGapCount,
    long payloadBytes,
    String sha256,
    String detail) {

  // Normalize required and optional fields.
  public RecordingInspection {
    Objects.requireNonNull(format, "format");
    Objects.requireNonNull(integrity, "integrity");
    sha256 = sha256 == null ? "" : sha256;
    detail = detail == null ? "" : detail;
  }

  /** Returns whether this file proves a clean version-2 finalization. */
  public boolean complete() {
    return integrity == RecordingIntegrity.COMPLETE;
  }
}
