package org.hammer.audio.experiment.document;

import java.util.List;
import java.util.Objects;
import org.hammer.audio.plugin.document.DocumentDiagnostic;

/**
 * Immutable result of matching portable requirements to local machine resources.
 *
 * @param bindings normalized local asset and output bindings
 * @param diagnostics immutable readiness diagnostics
 * @param ready whether the document and bindings are ready for application or execution
 */
public record ExperimentLocalBindingPreview(
    ExperimentLocalBindings bindings, List<DocumentDiagnostic> diagnostics, boolean ready) {

  /** Defensively copy local-binding diagnostics. */
  public ExperimentLocalBindingPreview {
    Objects.requireNonNull(bindings, "bindings");
    diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
  }
}
