package org.hammer.audio.recording.runtime;

/** Receives immutable recording status transitions and metric snapshots. */
@FunctionalInterface
public interface RecordingStatusListener {

  /** Called from the recorder worker or the thread that initiates a lifecycle transition. */
  void onRecordingStatus(RecordingStatus status);
}
