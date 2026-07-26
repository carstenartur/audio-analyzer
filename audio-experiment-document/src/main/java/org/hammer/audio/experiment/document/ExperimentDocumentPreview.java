package org.hammer.audio.experiment.document;

import java.util.List;
import java.util.Objects;
import org.hammer.audio.plugin.document.DocumentDiagnostic;

/**
 * Safe, non-mutating result of document parsing, plugin resolution and normalization.
 *
 * @param document normalized document copy
 * @param canonicalSha256 normalized document hash
 * @param diagnostics structured import diagnostics
 * @param migrations deterministic migrations that would be applied on save-as
 * @param executionAllowed whether every required capability is available and valid
 * @param readOnly whether destructive save/application must remain disabled
 */
public record ExperimentDocumentPreview(
    ExperimentDocument document,
    String canonicalSha256,
    List<DocumentDiagnostic> diagnostics,
    List<String> migrations,
    boolean executionAllowed,
    boolean readOnly) {

  /* Validate and defensively copy preview state. */
  public ExperimentDocumentPreview {
    Objects.requireNonNull(document, "document");
    canonicalSha256 = ExperimentDocument.requireSha256(canonicalSha256, "canonicalSha256");
    diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    migrations = List.copyOf(Objects.requireNonNull(migrations, "migrations"));
  }
}
