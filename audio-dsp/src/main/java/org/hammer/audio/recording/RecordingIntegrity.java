package org.hammer.audio.recording;

/** Integrity result obtained while reading or inspecting a recording. */
public enum RecordingIntegrity {
  /** Legacy version-1 file has no footer and therefore cannot prove clean finalization. */
  LEGACY_UNVERIFIED,
  /** Version-2 footer, counters and SHA-256 digest are valid. */
  COMPLETE,
  /** Complete frame records were recovered but the final footer is absent. */
  RECOVERABLE_INCOMPLETE,
  /** Structure or digest is invalid. */
  CORRUPT
}
