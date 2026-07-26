package org.hammer.audio.experiment.document.workspace;

import java.util.Objects;

/** Stable application failure for a rejected destructive experiment-document apply operation. */
public final class ExperimentDocumentApplyException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  private final String code;

  /** Create a coded apply rejection. */
  public ExperimentDocumentApplyException(String code, String message) {
    super(message);
    this.code = Objects.requireNonNull(code, "code");
  }

  /** Stable machine-readable rejection code. */
  public String code() {
    return code;
  }
}
