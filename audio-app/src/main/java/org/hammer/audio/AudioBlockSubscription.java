package org.hammer.audio;

/** Removable subscription to an {@link AudioCaptureService}'s complete audio-block stream. */
public interface AudioBlockSubscription extends AutoCloseable {

  /** Remove the listener. Safe to call repeatedly. */
  @Override
  void close();

  /** Returns whether this subscription has already been removed. */
  boolean isClosed();
}
