package org.hammer;

import java.nio.file.Path;
import java.util.Locale;
import org.hammer.audio.recording.runtime.RecordingState;
import org.hammer.audio.recording.runtime.RecordingStatus;
import org.hammer.audio.recording.runtime.RecordingStorageStatus;

/** Readable Swing projection of the actual recorder snapshot, including terminal loss evidence. */
final class RecordingStatusText {

  private static final String COUNT_SEPARATOR = " / ";

  private RecordingStatusText() {
    // Shared text projection, with no filesystem access or derived recorder state.
  }

  static String summary(RecordingStatus recording) {
    if (recording == null) {
      return "Recording: not active";
    }
    Path filename = recording.destination().getFileName();
    return (recording.state() == RecordingState.RECORDING ? "● " : "")
        + "Recording: "
        + recording.state()
        + " · File: "
        + (filename == null ? recording.destination() : filename)
        + " · Elapsed: "
        + duration(recording.elapsed().toSeconds())
        + "\nWritten: "
        + bytes(recording.bytesWritten())
        + " · Rate: "
        + rate(recording.measuredBytesPerSecond())
        + " · Usable: "
        + bytes(recording.storage().usableBytes())
        + " · Safe time: "
        + duration(recording.storage().estimatedSafeSecondsRemaining())
        + "\nWritten / received: "
        + recording.writtenFrames()
        + COUNT_SEPARATOR
        + recording.receivedFrames()
        + " frames · "
        + recording.writtenBlocks()
        + COUNT_SEPARATOR
        + recording.receivedBlocks()
        + " blocks"
        + "\nQueue: "
        + recording.queueDepth()
        + COUNT_SEPARATOR
        + recording.queueCapacity()
        + " · Dropped blocks / frames: "
        + recording.droppedBlocks()
        + COUNT_SEPARATOR
        + recording.droppedFrames()
        + " · Continuity gaps: "
        + recording.continuityGapCount()
        + "\nStorage: "
        + recording.storage().level()
        + " · Capacity warnings: "
        + recording.storage().warningHistory().size()
        + " · Loss: "
        + (recording.completeSoFar() ? "none observed" : "incomplete or failed")
        + (recording.stopReason().isBlank() ? "" : "\nStop reason: " + recording.stopReason())
        + (recording.errorMessage().isBlank() ? "" : "\nError: " + recording.errorMessage());
  }

  static String details(RecordingStatus recording) {
    if (recording == null) {
      return "Recording: not active";
    }
    RecordingStorageStatus storage = recording.storage();
    return summary(recording)
        + "\nDestination: "
        + recording.destination()
        + "\nRecorder started: "
        + recording.startedAt()
        + "\nRecorder observed: "
        + recording.updatedAt()
        + "\nSerialized bytes: "
        + recording.bytesWritten()
        + "\nMaximum queue depth: "
        + recording.maximumQueueDepth()
        + "\nBacking store: "
        + storage.storeName()
        + " ("
        + storage.storeType()
        + ")"
        + "\nWritable: "
        + storage.writable()
        + "\nCapacity bytes (usable / unallocated / total): "
        + storage.usableBytes()
        + COUNT_SEPARATOR
        + storage.unallocatedBytes()
        + COUNT_SEPARATOR
        + storage.totalBytes()
        + "\nExpected write rate: "
        + rate(storage.expectedBytesPerSecond())
        + "\nLast successful capacity check: "
        + storage.lastSuccessfulCheck()
        + "\nCapacity observed: "
        + storage.checkedAt()
        + "\nCapacity query error: "
        + storage.errorMessage()
        + "\nCapacity policy: "
        + storage.policy()
        + "\nCapacity warning history: "
        + storage.warningHistory();
  }

  private static String rate(double value) {
    return Double.isFinite(value) && value >= 0.0 ? bytes(Math.round(value)) + "/s" : "unknown";
  }

  private static String duration(long seconds) {
    if (seconds < 0L) {
      return "unknown";
    }
    return String.format(
        Locale.ROOT, "%02d:%02d:%02d", seconds / 3600L, seconds / 60L % 60L, seconds % 60L);
  }

  private static String bytes(long bytes) {
    if (bytes < 0L) {
      return "unknown";
    }
    if (bytes < 1024L) {
      return bytes + " B";
    }
    double value = bytes;
    String[] units = {"KiB", "MiB", "GiB", "TiB"};
    int unit = -1;
    do {
      value /= 1024.0;
      unit++;
    } while (value >= 1024.0 && unit + 1 < units.length);
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
  }
}
