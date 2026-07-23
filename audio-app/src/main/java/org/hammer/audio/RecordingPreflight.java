package org.hammer.audio;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.hammer.audio.recording.runtime.FileStoreRecordingStorageProbe;
import org.hammer.audio.recording.runtime.RecordingStorageLevel;
import org.hammer.audio.recording.runtime.RecordingStorageProbe;
import org.hammer.audio.recording.runtime.RecordingStorageStatus;

/** Evaluates recording storage readiness before a target or partial file is created. */
public final class RecordingPreflight {

  private RecordingPreflight() {
    // utility class
  }

  /**
   * Inspect the selected destination using the configured source format and production filesystem
   * probe.
   *
   * @param service configured audio source
   * @param destination requested final recording path
   * @return immutable preflight capacity snapshot
   * @throws IOException if the source format or destination filesystem cannot be inspected
   */
  public static RecordingStorageStatus inspect(AudioCaptureService service, Path destination)
      throws IOException {
    return inspect(service, destination, new FileStoreRecordingStorageProbe(), Instant.now());
  }

  static RecordingStorageProbe productionProbe() {
    return new FileStoreRecordingStorageProbe();
  }

  static RecordingStorageStatus inspect(
      AudioCaptureService service,
      Path destination,
      RecordingStorageProbe storageProbe,
      Instant checkedAt)
      throws IOException {
    Objects.requireNonNull(service, "service");
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(storageProbe, "storageProbe");
    Objects.requireNonNull(checkedAt, "checkedAt");
    AudioFormatDescriptor descriptor = service.getDescriptor();
    if (descriptor == null) {
      throw new IOException("Audio source has no format descriptor; start/configure it first.");
    }
    return storageProbe.probe(destination, 0L, 0.0, expectedBytesPerSecond(descriptor), checkedAt);
  }

  static void requireReady(RecordingStorageStatus preflight) throws IOException {
    Objects.requireNonNull(preflight, "preflight");
    if (!preflight.writable()) {
      throw new IOException("Recording destination is not writable: " + preflight.destination());
    }
    if (preflight.level() == RecordingStorageLevel.CRITICAL) {
      throw new IOException(
          "Recording destination has insufficient safe capacity: "
              + preflight.usableBytes()
              + " usable bytes");
    }
  }

  private static double expectedBytesPerSecond(AudioFormatDescriptor descriptor) {
    double samplePayload = descriptor.sampleRate() * descriptor.channels() * Float.BYTES;
    return samplePayload * 1.02;
  }
}
