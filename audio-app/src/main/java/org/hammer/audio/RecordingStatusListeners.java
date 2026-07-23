package org.hammer.audio;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.hammer.audio.recording.runtime.RecordingStatus;

/** Thread-safe status-listener registry with failure isolation. */
final class RecordingStatusListeners {

  private static final Logger LOGGER = Logger.getLogger(RecordingStatusListeners.class.getName());

  private final List<RecordingTap.StatusListener> listeners = new CopyOnWriteArrayList<>();

  void add(RecordingTap.StatusListener listener) {
    listeners.add(Objects.requireNonNull(listener, "listener"));
  }

  void remove(RecordingTap.StatusListener listener) {
    listeners.remove(listener);
  }

  void publish(RecordingStatus status) {
    for (RecordingTap.StatusListener listener : listeners) {
      try {
        listener.onRecordingStatus(status);
      } catch (RuntimeException exception) {
        LOGGER.log(Level.WARNING, "Recording status listener failed", exception);
      }
    }
  }
}
