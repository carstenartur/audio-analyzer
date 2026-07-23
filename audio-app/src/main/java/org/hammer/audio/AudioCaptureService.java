package org.hammer.audio;

import javax.sound.sampled.AudioFormat;
import org.hammer.audio.buffer.AudioRingBuffer;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;

/**
 * Service interface for audio capture and waveform data management.
 *
 * <p>This interface defines the contract for starting/stopping audio capture, retrieving the latest
 * waveform model snapshots, subscribing to the complete block stream and adjusting capture
 * parameters.
 *
 * <p>Thread-safety: Implementations must ensure thread-safe access to all methods. Model snapshots
 * returned by {@link #getLatestModel()} should be immutable or defensive copies to prevent
 * concurrent modification issues.
 *
 * @author refactoring
 */
public interface AudioCaptureService {

  /**
   * Start audio capture.
   *
   * <p>Initializes audio input device and begins capturing audio data in a background thread.
   *
   * @throws IllegalStateException if the service is already started or if the audio device cannot
   *     be initialized
   */
  void start();

  /**
   * Stop audio capture.
   *
   * <p>Gracefully stops the capture thread and releases audio device resources. This method should
   * be idempotent - calling it multiple times should be safe.
   */
  void stop();

  /** Check if audio capture is currently running. */
  boolean isRunning();

  /**
   * Get the latest waveform model snapshot.
   *
   * <p>Returns an immutable snapshot of the current waveform data. This method must be thread-safe
   * and return defensive copies to prevent concurrent modification.
   */
  WaveformModel getLatestModel();

  /** Get the JavaSound format used for capture, or {@code null} if not initialized. */
  AudioFormat getFormat();

  /**
   * Set the divisor for buffer size calculation.
   *
   * @param divisor the divisor value (must be >= 1)
   */
  void setDivisor(int divisor);

  /** Get the current divisor value. */
  int getDivisor();

  /** Recompute layout/coordinates based on current panel dimensions. */
  void recomputeLayout(int width, int height);

  /**
   * @return the audio-domain format descriptor, or {@code null} if the service has not been started
   *     yet. Unlike {@link #getFormat()}, this is platform-internal and free of JavaSound types.
   */
  default AudioFormatDescriptor getDescriptor() {
    return null;
  }

  /**
   * @return the most recently captured {@link AudioBlock}, or {@code null} if no audio has been
   *     captured yet. The returned block is immutable and safe to share.
   */
  default AudioBlock getLatestBlock() {
    return null;
  }

  /**
   * Subscribe to every block published after registration.
   *
   * <p>Listeners run on the source producer thread and must return promptly. A recorder therefore
   * only enqueues in the callback and performs serialization on its own worker.
   *
   * @throws UnsupportedOperationException if an implementation does not expose a complete stream
   */
  default AudioBlockSubscription subscribe(AudioBlockListener listener) {
    throw new UnsupportedOperationException(
        getClass().getName() + " does not support complete audio-block subscriptions");
  }

  /**
   * @return the producer/consumer ring buffer fed by the capture thread, or {@code null} if the
   *     service has not been started yet. Downstream DSP and analysis modules consume blocks from
   *     this buffer.
   */
  default AudioRingBuffer<AudioBlock> getRingBuffer() {
    return null;
  }
}
