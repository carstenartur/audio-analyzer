package org.hammer.audio.recording;

import java.util.Objects;
import org.hammer.audio.core.AudioFormatDescriptor;

/** Immutable structural and integrity summary for one recording. */
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
