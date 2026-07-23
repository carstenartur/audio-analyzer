package org.hammer.audio.plugin.document;

import java.util.List;
import java.util.Objects;

/**
 * Side-effect-free plugin-section validation result.
 *
 * @param normalizedValue deterministic normalized section value
 * @param diagnostics immutable diagnostics
 */
public record DocumentValidationResult(
    DocumentValue normalizedValue, List<DocumentDiagnostic> diagnostics) {

  // Validate and defensively copy the result.
  public DocumentValidationResult {
    Objects.requireNonNull(normalizedValue, "normalizedValue");
    diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
  }

  /** Return a successful result without diagnostics. */
  public static DocumentValidationResult valid(DocumentValue normalizedValue) {
    return new DocumentValidationResult(normalizedValue, List.of());
  }

  /** Return whether any diagnostic blocks execution or application. */
  public boolean hasErrors() {
    return diagnostics.stream()
        .anyMatch(diagnostic -> diagnostic.severity() == DocumentDiagnostic.Severity.ERROR);
  }
}
