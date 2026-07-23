package org.hammer.audio.recording.runtime;

/** Severity derived from writable state and estimated safe recording duration. */
public enum RecordingStorageLevel {
  NORMAL,
  WARNING,
  CRITICAL,
  UNKNOWN
}
