package org.hammer.audio;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.hammer.audio.recording.AudioBlockRecordingWriter;
import org.hammer.audio.recording.runtime.FileStoreRecordingStorageProbe;
import org.hammer.audio.recording.runtime.RecordingState;
import org.hammer.audio.recording.runtime.RecordingStatus;
import org.hammer.audio.recording.runtime.RecordingStatusListener;
import org.hammer.audio.recording.runtime.RecordingStorageLevel;
import org.hammer.audio.recording.runtime.RecordingStorageProbe;
import org.hammer.audio.recording.runtime.RecordingStorageStatus;

/**
 * Loss-aware recorder that subscribes to every produced {@link AudioBlock}, enqueues without
 * blocking the capture thread and serializes on a dedicated I/O worker.
 */
public final class RecordingTap {

  private static final Logger LOGGER = Logger.getLogger(RecordingTap.class.getName());
  private static final int DEFAULT_QUEUE_CAPACITY = 512;
  private static final long STATUS_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(100L);
  private static final long STORAGE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1L);
  private static final long STOP_TIMEOUT_SECONDS = 10L;

  private final AudioBlockRecordingWriter writer;
  private final Path file;
  private final ArrayBlockingQueue<AudioBlock> queue;
  private final RecordingStorageProbe storageProbe;
  private final double expectedBytesPerSecond;
  private final Instant startedAt;
  private final ExecutorService writerExecutor;
  private final CountDownLatch completed = new CountDownLatch(1);
  private final CopyOnWriteArrayList<RecordingStatusListener> listeners =
      new CopyOnWriteArrayList<>();
  private final AtomicReference<RecordingStatus> currentStatus;
  private final AtomicReference<RecordingStorageStatus> storageStatus;
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final AtomicBoolean accepting = new AtomicBoolean(true);
  private final AtomicBoolean stopRequested = new AtomicBoolean(false);
  private final AtomicBoolean incomplete = new AtomicBoolean(false);
  private final AtomicLong receivedBlocks = new AtomicLong();
  private final AtomicLong receivedFrames = new AtomicLong();
  private final AtomicLong writtenBlocks = new AtomicLong();
  private final AtomicLong writtenFrames = new AtomicLong();
  private final AtomicLong droppedBlocks = new AtomicLong();
  private final AtomicLong droppedFrames = new AtomicLong();
  private final AtomicInteger maximumQueueDepth = new AtomicInteger();
  private final AtomicLong lastStatusPublishedNanos = new AtomicLong();

  private volatile AudioBlockSubscription subscription;
  private volatile String stopReason = "";
  private volatile double measuredBytesPerSecond;

  /**
   * Compatibility overload. The historic poll interval is ignored because recording now consumes
   * the complete subscribed block stream.
   */
  public static RecordingTap start(AudioCaptureService service, Path file, int pollIntervalMs)
      throws IOException {
    if (pollIntervalMs < 1) {
      throw new IllegalArgumentException("pollIntervalMs must be >= 1, was " + pollIntervalMs);
    }
    return start(service, file);
  }

  /** Start a loss-aware recording with production storage probing and queue defaults. */
  public static RecordingTap start(AudioCaptureService service, Path file) throws IOException {
    return start(
        service,
        file,
        DEFAULT_QUEUE_CAPACITY,
        new FileStoreRecordingStorageProbe(),
        Instant.now());
  }

  static RecordingTap start(
      AudioCaptureService service,
      Path file,
      int queueCapacity,
      RecordingStorageProbe storageProbe,
      Instant startedAt)
      throws IOException {
    Objects.requireNonNull(service, "service");
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(storageProbe, "storageProbe");
    Objects.requireNonNull(startedAt, "startedAt");
    if (queueCapacity < 1) {
      throw new IllegalArgumentException("queueCapacity must be >= 1");
    }
    AudioFormatDescriptor descriptor = service.getDescriptor();
    if (descriptor == null) {
      throw new IOException("Audio source has no format descriptor; start/configure it first.");
    }
    double expectedRate = expectedBytesPerSecond(descriptor);
    RecordingStorageStatus preflight =
        storageProbe.probe(file, 0L, 0.0, expectedRate, startedAt);
    if (!preflight.writable()) {
      throw new IOException("Recording destination is not writable: " + file);
    }
    if (preflight.level() == RecordingStorageLevel.CRITICAL) {
      throw new IOException(
          "Recording destination has insufficient safe capacity: "
              + preflight.usableBytes()
              + " usable bytes");
    }

    AudioBlockRecordingWriter writer = AudioBlockRecordingWriter.open(file);
    RecordingTap tap =
        new RecordingTap(
            writer,
            file.toAbsolutePath().normalize(),
            queueCapacity,
            storageProbe,
            expectedRate,
            startedAt,
            preflight);
    tap.writerExecutor.submit(tap::writerLoop);
    try {
      tap.subscription = service.subscribe(tap::acceptBlock);
    } catch (RuntimeException exception) {
      tap.requestFailure(exception, "Audio source does not provide a complete recording stream.");
      tap.awaitCompletion();
      throw new IOException("Failed to subscribe recorder to audio source", exception);
    }
    tap.publishStatus(RecordingState.RECORDING, true);
    return tap;
  }

  private RecordingTap(
      AudioBlockRecordingWriter writer,
      Path file,
      int queueCapacity,
      RecordingStorageProbe storageProbe,
      double expectedBytesPerSecond,
      Instant startedAt,
      RecordingStorageStatus preflight) {
    this.writer = writer;
    this.file = file;
    this.queue = new ArrayBlockingQueue<>(queueCapacity);
    this.storageProbe = storageProbe;
    this.expectedBytesPerSecond = expectedBytesPerSecond;
    this.startedAt = startedAt;
    this.storageStatus = new AtomicReference<>(preflight);
    this.writerExecutor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "ExperimentRecordingWriter");
              thread.setDaemon(true);
              return thread;
            });
    this.currentStatus =
        new AtomicReference<>(snapshot(RecordingState.STARTING, startedAt, "", ""));
  }

  /** Destination target. The finalized file appears only after a successful close. */
  public Path file() {
    return file;
  }

  /** Number of blocks successfully serialized so far. */
  public long blocksWritten() {
    return writtenBlocks.get();
  }

  /** Current immutable status snapshot. */
  public RecordingStatus status() {
    return currentStatus.get();
  }

  /** Register a status listener and immediately deliver the current snapshot. */
  public void addStatusListener(RecordingStatusListener listener) {
    RecordingStatusListener required = Objects.requireNonNull(listener, "listener");
    listeners.add(required);
    required.onRecordingStatus(currentStatus.get());
  }

  /** Remove a previously registered listener. */
  public void removeStatusListener(RecordingStatusListener listener) {
    listeners.remove(listener);
  }

  /** Returns whether recording reached a terminal state. */
  public boolean isClosed() {
    return currentStatus.get().terminal();
  }

  private void acceptBlock(AudioBlock block) {
    if (!accepting.get()) {
      return;
    }
    receivedBlocks.incrementAndGet();
    receivedFrames.addAndGet(block.frames());
    if (!queue.offer(block)) {
      droppedBlocks.incrementAndGet();
      droppedFrames.addAndGet(block.frames());
      incomplete.set(true);
      stopReason = "Recorder queue overflowed; recording is incomplete.";
      publishStatus(RecordingState.RECORDING, true);
      return;
    }
    maximumQueueDepth.accumulateAndGet(queue.size(), Math::max);
    publishStatus(RecordingState.RECORDING, false);
  }

  private void writerLoop() {
    long previousRateBytes = 0L;
    long previousRateNanos = System.nanoTime();
    long nextStorageProbeNanos = previousRateNanos;
    try {
      while (accepting.get() || !queue.isEmpty()) {
        AudioBlock block = queue.poll(100L, TimeUnit.MILLISECONDS);
        if (block != null) {
          writer.write(block);
          writtenBlocks.incrementAndGet();
          writtenFrames.addAndGet(block.frames());
          if (writer.continuityGapCount() > 0L) {
            incomplete.set(true);
          }
        }
        long nowNanos = System.nanoTime();
        if (nowNanos >= nextStorageProbeNanos) {
          long bytes = writer.bytesWritten();
          long elapsedNanos = nowNanos - previousRateNanos;
          if (elapsedNanos > 0L) {
            measuredBytesPerSecond =
                (bytes - previousRateBytes) * 1_000_000_000.0 / elapsedNanos;
          }
          previousRateBytes = bytes;
          previousRateNanos = nowNanos;
          refreshStorage(bytes);
          nextStorageProbeNanos = nowNanos + STORAGE_INTERVAL_NANOS;
        }
        RecordingStorageStatus storage = storageStatus.get();
        if (storage.critical()) {
          incomplete.set(true);
          stopReason = "Recording stopped before the destination filesystem was exhausted.";
          requestStopFromWorker();
        }
        publishStatus(
            stopRequested.get() ? RecordingState.STOPPING : RecordingState.RECORDING, false);
      }
      finalizeWriter();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      incomplete.set(true);
      requestFailure(exception, "Recorder worker was interrupted.");
      abortWriter();
    } catch (IOException | RuntimeException exception) {
      requestFailure(exception, "Recording write failed.");
      abortWriter();
    } finally {
      finishStatus();
      completed.countDown();
      writerExecutor.shutdown();
    }
  }

  private void finalizeWriter() throws IOException {
    if (writtenBlocks.get() == 0L) {
      incomplete.set(true);
      stopReason = stopReason.isBlank() ? "No audio blocks were written." : stopReason;
      writer.abort();
      return;
    }
    writer.close();
  }

  private void abortWriter() {
    try {
      writer.abort();
    } catch (IOException exception) {
      Throwable primary = failure.get();
      if (primary != null) {
        primary.addSuppressed(exception);
      } else {
        failure.set(exception);
      }
    }
  }

  private void refreshStorage(long bytesWritten) {
    try {
      storageStatus.set(
          storageProbe.probe(
              file,
              bytesWritten,
              measuredBytesPerSecond,
              expectedBytesPerSecond,
              Instant.now()));
    } catch (IOException exception) {
      storageStatus.set(
          new RecordingStorageStatus(
              file,
              "",
              "",
              false,
              -1L,
              -1L,
              -1L,
              bytesWritten,
              measuredBytesPerSecond,
              expectedBytesPerSecond,
              -1L,
              RecordingStorageLevel.UNKNOWN,
              Instant.now(),
              exception.getMessage()));
    }
  }

  /** Stop accepting new blocks, drain the queue and finalize exactly once. */
  public void stop() throws IOException {
    if (isClosed()) {
      return;
    }
    stopReason = stopReason.isBlank() ? "Stopped by user." : stopReason;
    stopRequested.set(true);
    accepting.set(false);
    closeSubscription();
    publishStatus(RecordingState.STOPPING, true);
    awaitCompletion();
    Throwable terminalFailure = failure.get();
    if (terminalFailure != null) {
      if (terminalFailure instanceof IOException ioException) {
        throw ioException;
      }
      throw new IOException("Recording failed", terminalFailure);
    }
  }

  private void requestStopFromWorker() {
    if (stopRequested.compareAndSet(false, true)) {
      accepting.set(false);
      closeSubscription();
    }
  }

  private void requestFailure(Throwable exception, String reason) {
    failure.compareAndSet(null, exception);
    stopReason = reason;
    accepting.set(false);
    stopRequested.set(true);
    closeSubscription();
    LOGGER.log(Level.WARNING, reason, exception);
  }

  private void closeSubscription() {
    AudioBlockSubscription current = subscription;
    if (current != null) {
      current.close();
    }
  }

  private void awaitCompletion() throws IOException {
    try {
      if (!completed.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        incomplete.set(true);
        writerExecutor.shutdownNow();
        throw new IOException("Timed out while draining the recording queue");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while stopping recording", exception);
    }
  }

  private void finishStatus() {
    Throwable terminalFailure = failure.get();
    RecordingState terminalState;
    String errorMessage = "";
    if (terminalFailure != null) {
      terminalState = RecordingState.FAILED;
      errorMessage =
          terminalFailure.getMessage() == null
              ? terminalFailure.toString()
              : terminalFailure.getMessage();
    } else if (incomplete.get()
        || writer.continuityGapCount() > 0L
        || droppedBlocks.get() > 0L) {
      terminalState = RecordingState.INCOMPLETE;
    } else {
      terminalState = RecordingState.COMPLETED;
    }
    publishStatus(terminalState, true, errorMessage);
  }

  private void publishStatus(RecordingState state, boolean force) {
    publishStatus(state, force, "");
  }

  private void publishStatus(RecordingState state, boolean force, String errorMessage) {
    long nowNanos = System.nanoTime();
    long previous = lastStatusPublishedNanos.get();
    if (!force && previous != 0L && nowNanos - previous < STATUS_INTERVAL_NANOS) {
      return;
    }
    if (!lastStatusPublishedNanos.compareAndSet(previous, nowNanos) && !force) {
      return;
    }
    RecordingStatus status = snapshot(state, Instant.now(), stopReason, errorMessage);
    currentStatus.set(status);
    for (RecordingStatusListener listener : listeners) {
      try {
        listener.onRecordingStatus(status);
      } catch (RuntimeException exception) {
        LOGGER.log(Level.WARNING, "Recording status listener failed", exception);
      }
    }
  }

  private RecordingStatus snapshot(
      RecordingState state, Instant updatedAt, String reason, String errorMessage) {
    return new RecordingStatus(
        state,
        file,
        startedAt,
        updatedAt,
        receivedBlocks.get(),
        writtenBlocks.get(),
        receivedFrames.get(),
        writtenFrames.get(),
        droppedBlocks.get(),
        droppedFrames.get(),
        writer.continuityGapCount(),
        queue.size(),
        queue.remainingCapacity() + queue.size(),
        maximumQueueDepth.get(),
        writer.bytesWritten(),
        measuredBytesPerSecond,
        storageStatus.get(),
        reason,
        errorMessage);
  }

  private static double expectedBytesPerSecond(AudioFormatDescriptor descriptor) {
    double samplePayload = descriptor.sampleRate() * descriptor.channels() * Float.BYTES;
    return samplePayload * 1.02;
  }
}
