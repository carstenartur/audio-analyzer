package org.hammer.audio.experiment.document;

import java.io.IOException;

/** Checked validation or parsing failure for an untrusted experiment document. */
public final class ExperimentDocumentException extends IOException {

  private static final long serialVersionUID = 1L;

  private final String documentPointer;
  private final String diagnosticCode;

  /** Create a pointer-aware document failure. */
  public ExperimentDocumentException(String pointer, String code, String message) {
    super(message);
    this.documentPointer = pointer == null || pointer.isBlank() ? "/" : pointer;
    this.diagnosticCode = ExperimentDocument.requireIdentifier(code, "diagnostic code");
  }

  /** Create a pointer-aware document failure with an underlying parser error. */
  public ExperimentDocumentException(String pointer, String code, String message, Throwable cause) {
    super(message, cause);
    this.documentPointer = pointer == null || pointer.isBlank() ? "/" : pointer;
    this.diagnosticCode = ExperimentDocument.requireIdentifier(code, "diagnostic code");
  }

  /** JSON Pointer or semantic location of the failure. */
  public String pointer() {
    return documentPointer;
  }

  /** Stable machine-readable failure code. */
  public String code() {
    return diagnosticCode;
  }
}
