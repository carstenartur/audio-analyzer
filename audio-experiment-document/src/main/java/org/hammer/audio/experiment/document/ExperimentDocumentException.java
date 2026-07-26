package org.hammer.audio.experiment.document;

import java.io.IOException;

/** Checked validation or parsing failure for an untrusted experiment document. */
public final class ExperimentDocumentException extends IOException {

  private static final long serialVersionUID = 1L;

  private final String pointer;
  private final String code;

  /** Create a pointer-aware document failure. */
  public ExperimentDocumentException(String pointer, String code, String message) {
    super(message);
    this.pointer = pointer == null || pointer.isBlank() ? "/" : pointer;
    this.code = ExperimentDocument.requireIdentifier(code, "diagnostic code");
  }

  /** Create a pointer-aware document failure with an underlying parser error. */
  public ExperimentDocumentException(String pointer, String code, String message, Throwable cause) {
    super(message, cause);
    this.pointer = pointer == null || pointer.isBlank() ? "/" : pointer;
    this.code = ExperimentDocument.requireIdentifier(code, "diagnostic code");
  }

  /** JSON Pointer or semantic location of the failure. */
  public String pointer() {
    return pointer;
  }

  /** Stable machine-readable failure code. */
  public String code() {
    return code;
  }
}
