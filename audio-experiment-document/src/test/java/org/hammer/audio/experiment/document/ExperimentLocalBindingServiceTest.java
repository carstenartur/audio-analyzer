package org.hammer.audio.experiment.document;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hammer.audio.plugin.document.DocumentDiagnostic;
import org.hammer.audio.plugin.document.DocumentValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExperimentLocalBindingServiceTest {

  private static final String WORKFLOW =
      "workflow\n  id: binding.workflow\n  name: Binding workflow\n  nodes:\n  edges:\n";

  @TempDir Path temporaryDirectory;

  @Test
  void matchingAssetAndWritableOutputProduceReadyBinding() throws Exception {
    Path asset = temporaryDirectory.resolve("input.aarec");
    Files.writeString(asset, "portable asset");
    ExperimentDocumentPreview preview =
        preview(Files.size(asset), DocumentHashes.sha256(asset), true);
    ExperimentLocalBindings bindings =
        new ExperimentLocalBindings(Map.of("recording", asset), temporaryDirectory);

    ExperimentLocalBindingPreview result =
        new ExperimentLocalBindingService().inspect(preview, bindings);

    assertTrue(result.ready());
    assertTrue(result.diagnostics().isEmpty());
  }

  @Test
  void missingAssetAndOutputBindingsBlockReadinessWithoutWritingAnything() throws Exception {
    ExperimentDocumentPreview preview = preview(12L, "a".repeat(64), true);
    Path notCreated = temporaryDirectory.resolve("not-created").resolve("out");
    ExperimentLocalBindings bindings = new ExperimentLocalBindings(Map.of(), null);

    ExperimentLocalBindingPreview result =
        new ExperimentLocalBindingService().inspect(preview, bindings);

    assertFalse(result.ready());
    assertTrue(
        result.diagnostics().stream()
            .anyMatch(item -> item.code().equals("asset-binding-missing")));
    assertTrue(
        result.diagnostics().stream()
            .anyMatch(item -> item.code().equals("output-binding-missing")));
    assertFalse(Files.exists(notCreated));
  }

  @Test
  void mismatchedAssetAndUnusedBindingAreReportedPrecisely() throws Exception {
    Path asset = temporaryDirectory.resolve("wrong.aarec");
    Path unused = temporaryDirectory.resolve("unused.bin");
    Files.writeString(asset, "wrong");
    Files.writeString(unused, "unused");
    ExperimentDocumentPreview preview = preview(99L, "b".repeat(64), false);
    ExperimentLocalBindings bindings =
        new ExperimentLocalBindings(Map.of("recording", asset, "unused", unused), null);

    ExperimentLocalBindingPreview result =
        new ExperimentLocalBindingService().inspect(preview, bindings);

    assertFalse(result.ready());
    assertTrue(result.diagnostics().stream().anyMatch(item -> item.code().equals("asset-size-mismatch")));
    assertTrue(result.diagnostics().stream().anyMatch(item -> item.code().equals("asset-hash-mismatch")));
    assertTrue(
        result.diagnostics().stream()
            .anyMatch(
                item ->
                    item.severity() == DocumentDiagnostic.Severity.WARNING
                        && item.code().equals("unused-asset-binding")));
  }

  private static ExperimentDocumentPreview preview(long size, String sha256, boolean withOutput)
      throws Exception {
    ExperimentDocumentCodec codec = new ExperimentDocumentCodec();
    ExperimentDocument document =
        new ExperimentDocument(
            ExperimentDocumentFormat.SCHEMA_RESOURCE,
            ExperimentDocumentFormat.FORMAT_ID,
            ExperimentDocumentFormat.VERSION,
            new ExperimentDocument.ExperimentInfo(
                "binding.experiment",
                "Binding experiment",
                "Local binding fixture",
                List.of(),
                "analysis",
                "replay",
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
            List.of(
                new ExperimentDocument.AssetReference(
                    "recording",
                    "assets/input.aarec",
                    "application/vnd.carstenartur.audio-recording",
                    size,
                    sha256)),
            withOutput
                ? List.of(
                    new ExperimentDocument.OutputRequest(
                        "report", "text/markdown", "report.md"))
                : List.of(),
            new ExperimentDocument.Provenance(
                "Binding Test",
                "",
                Instant.parse("2026-07-23T12:00:00Z"),
                Instant.parse("2026-07-23T12:00:00Z"),
                "0.0.4-SNAPSHOT",
                "",
                List.of()));
    ExperimentDocument canonical = codec.decode(codec.encode(document));
    return PluginDocumentCatalog.empty().preview(canonical, codec);
  }
}
