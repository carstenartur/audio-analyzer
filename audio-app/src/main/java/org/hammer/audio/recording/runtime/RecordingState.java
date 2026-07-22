package org.hammer.audio.recording.runtime;

/** User-visible lifecycle of one experiment recording. */
public enum RecordingState {
  STARTING,
  RECORDING,
  STOPPING,
  COMPLETED,
  INCOMPLETE,
  FAILED
}
