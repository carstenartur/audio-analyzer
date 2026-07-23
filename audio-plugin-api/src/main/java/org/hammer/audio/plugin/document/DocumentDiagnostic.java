package org.hammer.audio.plugin.document;

import java.util.Objects;

/**
 * One side-effect-free validation or migration diagnostic.
 *
 * @param severity diagnostic severity
 * @param pointer JSON Pointer locating the affected value
 * @param code stable machine-readable diagnostic code
 * @param message human-readable diagnostic message
 */
public record DocumentDiagnostic(
    Severity severity, String pointer, String code, String message) {

  // Validate and normalize diagnostic fields.
  public DocumentDiagnostic {
    Objects.requireNonNull(severity, "severity");
    pointer = pointer == null || pointer.isBlank() ? "/" : pointer;
    code = requireNonBlank(code, "code");
    message = requireNonBlank(message, "message");
  }

  /** Diagnostic severity. */
  public enum Severity {
    /** Informational normalization or migration note. */
    INFO,
    /** Non-blocking portability or compatibility warning. */
    WARNING,
    /** Validation error that blocks execution or application. */
    ERROR
  }

  private static String requireNonBlank(String value, String label) {
    Objects.requireNonNull(value, label);
    if (value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value;
  }
}
