package org.hammer.audio.recording.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

/** API-neutral probe used for recording storage preflight and runtime monitoring. */
@FunctionalInterface
public interface RecordingStorageProbe {

  /** Inspect the backing store used by {@code destination}. */
  RecordingStorageStatus probe(
      Path destination,
      long bytesWritten,
      double measuredBytesPerSecond,
      double expectedBytesPerSecond,
      Instant checkedAt)
      throws IOException;
}
