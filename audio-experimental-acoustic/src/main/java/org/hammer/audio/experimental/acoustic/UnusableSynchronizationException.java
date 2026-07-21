package org.hammer.audio.experimental.acoustic;

/** Raised when synchronization quality is insufficient for a localization observation. */
public final class UnusableSynchronizationException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  /** Creates a synchronization rejection with a deterministic diagnostic. */
  public UnusableSynchronizationException(String message) {
    super(message);
  }
}
