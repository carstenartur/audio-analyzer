package org.hammer.audio;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.hammer.audio.recording.runtime.RecordingStatus;
import org.hammer.audio.recording.runtime.RecordingStatusListener;

/** Thread-safe status-listener registry with failure isolation. */
final class RecordingStatusListeners {

  private static final Logger LOGGER = Logger.getLogger(RecordingStatusListeners.class.getName());

  private final List<RecordingStatusListener> listeners = new CopyOnWriteArrayList<>();

  void add(RecordingStatusListener listener) {
    listeners.add(Objects.requireNonNull(listener, "listener"));
  }

  void remove(RecordingStatusListener listener) {
    listeners.remove(listener);
  }

  void publish(RecordingStatus status) {
    for (RecordingStatusListener listener : listeners) {
      try {
        listener.onRecordingStatus(status);
      } catch (RuntimeException exception) {
        LOGGER.log(Level.WARNING, "Recording status listener failed", exception);
      }
    }
  }
}
