package org.hammer.audio.experiment.document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hammer.audio.plugin.document.DocumentValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExperimentDocumentServiceTest {

  private static final String WORKFLOW =
      "workflow\n  id: service-workflow\n  name: Service workflow\n  nodes:\n  edges:\n";

  @TempDir Path temporaryDirectory;

  @Test
  void previewNormalizationWorkflowAndSchemaUseOneServicePath() throws Exception {
    ExperimentDocumentCodec codec = new ExperimentDocumentCodec();
    ExperimentDocumentService service = new ExperimentDocumentService(List.of());
    byte[] source = codec.encode(document());

    ExperimentDocumentPreview preview = service.preview(source);

    assertTrue(preview.executionAllowed());
    assertEquals("service-workflow", service.workflow(preview).id());
    assertArrayEquals(codec.encode(preview.document()), service.normalize(source));
    String schema = new String(service.schemaBytes(), StandardCharsets.UTF_8);
    assertTrue(schema.contains(ExperimentDocumentFormat.FORMAT_ID));
  }

  @Test
  void normalizeNeverRewritesImportedSourceImplicitly() throws Exception {
    ExperimentDocumentCodec codec = new ExperimentDocumentCodec();
    ExperimentDocumentService service = new ExperimentDocumentService(List.of());
    Path source = temporaryDirectory.resolve("source.audioexp");
    codec.save(source, document());
    byte[] original = Files.readAllBytes(source);

    assertThrows(IOException.class, () -> service.normalize(source, source));

    Path target = temporaryDirectory.resolve("normalized.audioexp");
    ExperimentDocumentPreview preview = service.normalize(source, target);
    assertArrayEquals(original, Files.readAllBytes(source));
    assertArrayEquals(codec.encode(preview.document()), Files.readAllBytes(target));
  }

  private static ExperimentDocument document() {
    return new ExperimentDocument(
        ExperimentDocumentFormat.SCHEMA_RESOURCE,
        ExperimentDocumentFormat.FORMAT_ID,
        ExperimentDocumentFormat.VERSION,
        new ExperimentDocument.ExperimentInfo(
            "service-experiment",
            "Service experiment",
            "Shared service fixture",
            List.of("fixture"),
            "analysis",
            "simulation",
            null,
            "0.0.4-SNAPSHOT"),
        new ExperimentDocument.WorkflowPayload(
            ExperimentDocumentFormat.WORKFLOW_FORMAT_ID,
            ExperimentDocumentFormat.WORKFLOW_VERSION,
            WORKFLOW,
            DocumentHashes.sha256(WORKFLOW)),
        DocumentValue.object(Map.of()),
        List.of(),
        Map.of(),
        List.of(),
        List.of(new ExperimentDocument.OutputRequest("report", "text/markdown", "report.md")),
        new ExperimentDocument.Provenance(
            "Test Author",
            "",
            Instant.parse("2026-07-23T12:00:00Z"),
            Instant.parse("2026-07-23T12:00:00Z"),
            "0.0.4-SNAPSHOT",
            "",
            List.of()));
  }
}
