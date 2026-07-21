package org.hammer.audio.experimental.acoustic;

/** Raised when a caller requires an unambiguous TDOA peak but the diagnostics reject it. */
public final class AmbiguousTdoaEstimateException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  /** Creates an ambiguity rejection with diagnostic context. */
  public AmbiguousTdoaEstimateException(String message) {
    super(message);
  }
}
