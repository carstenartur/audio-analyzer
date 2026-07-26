package org.hammer;

import java.nio.file.Path;
import org.hammer.audio.experiment.document.ExperimentDocumentPreview;
import org.hammer.audio.plugin.document.DocumentDiagnostic;
import org.hammer.audio.workflow.Workflow;

/** Deterministic plain-text projection of a portable experiment-document preview. */
final class ExperimentDocumentPreviewFormatter {

  private ExperimentDocumentPreviewFormatter() {
    // utility class
  }

  /** Format a copyable preview without exposing plugin implementation objects. */
  static String format(Path source, ExperimentDocumentPreview preview, Workflow workflow) {
    StringBuilder text = new StringBuilder();
    text.append("Source: ").append(source.toAbsolutePath().normalize()).append('\n');
    text.append("Experiment: ").append(preview.document().experiment().name()).append('\n');
    text.append("Experiment ID: ").append(preview.document().experiment().id()).append('\n');
    text.append("Format: ")
        .append(preview.document().format())
        .append(" v")
        .append(preview.document().formatVersion())
        .append('\n');
    text.append("Canonical SHA-256: ").append(preview.canonicalSha256()).append('\n');
    text.append("Source mode: ").append(preview.document().experiment().sourceMode()).append('\n');
    text.append("Workflow: ")
        .append(workflow.name())
        .append(" (")
        .append(workflow.nodes().size())
        .append(" nodes, ")
        .append(workflow.edges().size())
        .append(" edges)\n");
    text.append("Execution allowed: ").append(preview.executionAllowed()).append('\n');
    text.append("Read-only: ").append(preview.readOnly()).append('\n');
    appendMigrations(text, preview);
    appendDiagnostics(text, preview);
    if (preview.readOnly()) {
      text.append(
          "\nThis document may be inspected and preserved, but it cannot be applied or executed"
              + " with the current plugin environment.\n");
    }
    return text.toString();
  }

  private static void appendMigrations(
      StringBuilder text, ExperimentDocumentPreview preview) {
    if (preview.migrations().isEmpty()) {
      return;
    }
    text.append("\nMigrations:\n");
    preview.migrations().forEach(value -> text.append("- ").append(value).append('\n'));
  }

  private static void appendDiagnostics(
      StringBuilder text, ExperimentDocumentPreview preview) {
    if (preview.diagnostics().isEmpty()) {
      return;
    }
    text.append("\nDiagnostics:\n");
    for (DocumentDiagnostic diagnostic : preview.diagnostics()) {
      text.append("- [")
          .append(diagnostic.severity())
          .append("] ")
          .append(diagnostic.pointer())
          .append(" ")
          .append(diagnostic.code())
          .append(": ")
          .append(diagnostic.message())
          .append('\n');
    }
  }
}
