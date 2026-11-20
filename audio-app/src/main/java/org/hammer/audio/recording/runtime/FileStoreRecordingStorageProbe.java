package org.hammer.audio.recording.runtime;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Recording-storage probe backed by {@link FileStore}. */
public final class FileStoreRecordingStorageProbe implements RecordingStorageProbe {

  private final RecordingStoragePolicy policy;
  private final StoreLookup stores;

  /** Obtain a backing store; injectable for deterministic capacity and quota tests. */
  @FunctionalInterface
  public interface StoreLookup {
    /** Resolve the store containing the destination's existing parent. */
    FileStore find(Path existingParent) throws IOException;
  }

  /** Use production filesystem queries and configured recording margins. */
  public FileStoreRecordingStorageProbe() {
    this(RecordingStoragePolicy.configured(), Files::getFileStore);
  }

  /** Use an explicit capacity policy and filesystem boundary. */
  public FileStoreRecordingStorageProbe(RecordingStoragePolicy policy, StoreLookup stores) {
    this.policy = Objects.requireNonNull(policy, "policy");
    this.stores = Objects.requireNonNull(stores, "stores");
  }

  @Override
  public RecordingStorageStatus probe(
      Path destination,
      long bytesWritten,
      double measuredBytesPerSecond,
      double expectedBytesPerSecond,
      Instant checkedAt)
      throws IOException {
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(checkedAt, "checkedAt");
    Path normalized = destination.toAbsolutePath().normalize();
    Path existing = nearestExistingPath(normalized.getParent());
    FileStore store = stores.find(existing);
    long usable = store.getUsableSpace();
    long unallocated = store.getUnallocatedSpace();
    long total = store.getTotalSpace();
    boolean writable =
        !store.isReadOnly() && Files.isDirectory(existing) && Files.isWritable(existing);
    long remainingSeconds =
        policy.safeSeconds(usable, measuredBytesPerSecond, expectedBytesPerSecond);
    RecordingStorageLevel level =
        policy.level(writable, usable, measuredBytesPerSecond, expectedBytesPerSecond);
    return new RecordingStorageStatus(
        normalized,
        store.name(),
        store.type(),
        writable,
        usable,
        unallocated,
        total,
        bytesWritten,
        measuredBytesPerSecond,
        expectedBytesPerSecond,
        remainingSeconds,
        level,
        checkedAt,
        "",
        policy,
        checkedAt,
        List.of());
  }

  private static Path nearestExistingPath(Path path) throws IOException {
    Path candidate = path;
    while (candidate != null && !Files.exists(candidate)) {
      candidate = candidate.getParent();
    }
    if (candidate == null) {
      throw new IOException("No existing ancestor for recording destination: " + path);
    }
    return candidate;
  }
}
