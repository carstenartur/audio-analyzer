package org.hammer.audio;

import org.hammer.audio.core.AudioBlock;

/** Receives every immutable audio block published by one capture service. */
@FunctionalInterface
public interface AudioBlockListener {

  /**
   * Called on the source's producer thread.
   *
   * <p>Implementations must return promptly and must not perform blocking I/O. A listener that needs
   * slower processing should enqueue the block for its own worker.
   *
   * @param block immutable newly published source block
   */
  void onAudioBlock(AudioBlock block);
}
