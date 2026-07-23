package org.hammer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hammer.audio.experiment.document.ExperimentDocument;
import org.hammer.audio.experiment.document.ExperimentDocumentFormat;
import org.hammer.audio.experiment.document.ExperimentDocumentPreview;
import org.hammer.audio.plugin.document.DocumentDiagnostic;
import org.hammer.audio.plugin.document.DocumentValue;
import org.hammer.audio.workflow.Workflow;
import org.junit.jupiter.api.Test;

class ExperimentDocumentPreviewFormatterTest {

  @Test
  void previewContainsIdentityHashWorkflowAndPointerDiagnostics() {
    ExperimentDocumentPreview preview =
        new ExperimentDocumentPreview(
            document(),
            "1".repeat(64),
            List.of(
                new DocumentDiagnostic(
                    DocumentDiagnostic.Severity.WARNING,
                    "/pluginData/example",
                    "missing-plugin",
                    "Plugin is unavailable")),
            List.of("example:1->2"),
            true,
            true);
    Workflow workflow = new Workflow("workflow", "Preview workflow", List.of(), List.of());

    String text =
        ExperimentDocumentPreviewFormatter.format(
            Path.of("example.audioexp"), preview, workflow);

    assertTrue(text.contains("Preview experiment"));
    assertTrue(text.contains("1".repeat(64)));
    assertTrue(text.contains("Preview workflow (0 nodes, 0 edges)"));
    assertTrue(text.contains("example:1->2"));
    assertTrue(text.contains("/pluginData/example missing-plugin"));
    assertTrue(text.contains("cannot be applied or executed"));
  }

  private static ExperimentDocument document() {
    return new ExperimentDocument(
        ExperimentDocumentFormat.SCHEMA_RESOURCE,
        ExperimentDocumentFormat.FORMAT_ID,
        ExperimentDocumentFormat.VERSION,
        new ExperimentDocument.ExperimentInfo(
            "preview.experiment",
            "Preview experiment",
            "Preview fixture",
            List.of(),
            "analysis",
            "simulation",
            null,
            "0.0.4-SNAPSHOT"),
        new ExperimentDocument.WorkflowPayload(
            ExperimentDocumentFormat.WORKFLOW_FORMAT_ID,
            ExperimentDocumentFormat.WORKFLOW_VERSION,
            "workflow\n  id: workflow\n  name: Preview workflow\n  nodes:\n  edges:\n",
            "2".repeat(64)),
        DocumentValue.object(Map.of()),
        List.of(),
        Map.of(),
        List.of(),
        List.of(),
        new ExperimentDocument.Provenance(
            "Test",
            "",
            Instant.parse("2026-07-23T12:00:00Z"),
            Instant.parse("2026-07-23T12:00:00Z"),
            "0.0.4-SNAPSHOT",
            "1".repeat(64),
            List.of()));
  }
}
