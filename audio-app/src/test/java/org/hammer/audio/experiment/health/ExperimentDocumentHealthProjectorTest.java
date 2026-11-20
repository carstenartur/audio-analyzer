package org.hammer.audio.experiment.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hammer.audio.acquisition.health.ExperimentHealthState;
import org.hammer.audio.experiment.document.DocumentHashes;
import org.hammer.audio.experiment.document.ExperimentDocument;
import org.hammer.audio.experiment.document.ExperimentDocumentCodec;
import org.hammer.audio.experiment.document.ExperimentDocumentException;
import org.hammer.audio.experiment.document.ExperimentDocumentFormat;
import org.hammer.audio.experiment.document.ExperimentDocumentPreview;
import org.hammer.audio.experiment.document.ExperimentDocumentService;
import org.hammer.audio.experiment.document.ExperimentDocumentSession;
import org.hammer.audio.plugin.document.DocumentDiagnostic;
import org.hammer.audio.plugin.document.DocumentValue;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
import org.junit.jupiter.api.Test;

class ExperimentDocumentHealthProjectorTest {

  private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
  private static final String DSL =
      "workflow\n  id: experiment.workflow\n  name: Experiment workflow\n  nodes:\n  edges:\n";

  @Test
  void currentDocumentFactsStayCorrelatedAndNoExternalBindingsAreInvented() throws Exception {
    var preview = preview(List.of());
    var health =
        ExperimentDocumentHealthProjector.project(
            input(preview, false, DocumentHashes.sha256(DSL)), NOW);

    assertEquals(ExperimentHealthState.READY, health.state());
    assertEquals(preview.canonicalSha256(), health.facts().get("document.sha256"));
    assertEquals("7", health.facts().get("document.revision"));
    assertEquals("false", health.facts().get("document.dirty"));
    assertEquals("NONE_REQUIRED", health.facts().get("bindings.status"));
    assertEquals(DocumentHashes.sha256(DSL), health.facts().get("workflow.sha256"));
    assertTrue(health.startAllowed());
  }

  @Test
  void mismatchedWorkflowCannotBorrowDocumentReadiness() throws Exception {
    var health =
        ExperimentDocumentHealthProjector.project(
            input(preview(List.of()), true, "f".repeat(64)), NOW);

    assertEquals(ExperimentHealthState.BLOCKED, health.state());
    assertFalse(health.startAllowed());
    assertTrue(
        health.findings().stream()
            .anyMatch(item -> item.code().equals("workflow.document-mismatch")));
    assertTrue(health.findings().stream().anyMatch(item -> item.code().equals("document.dirty")));
  }

  @Test
  void missingPluginAndMigrationRemainVisibleWithDirectDocumentPointers() throws Exception {
    var original = preview(List.of());
    var incompatible =
        new ExperimentDocumentPreview(
            original.document(),
            original.canonicalSha256(),
            List.of(
                new DocumentDiagnostic(
                    DocumentDiagnostic.Severity.ERROR,
                    "/requiredPlugins/0",
                    "missing-plugin",
                    "Required plugin is unavailable")),
            List.of("example.settings:1->2"),
            false,
            true);
    var health =
        ExperimentDocumentHealthProjector.project(
            input(incompatible, false, DocumentHashes.sha256(DSL)), NOW);

    assertEquals(ExperimentHealthState.BLOCKED, health.state());
    assertTrue(
        health.findings().stream().anyMatch(item -> item.component().equals("/requiredPlugins/0")));
    assertEquals("example.settings:1->2", health.facts().get("document.migrations"));
  }

  @Test
  void portableAssetRequirementIsUnverifiedForTheCurrentSnapshot() throws Exception {
    var preview =
        preview(
            List.of(
                new ExperimentDocument.AssetReference(
                    "recording",
                    "capture.aarec",
                    "application/octet-stream",
                    20L,
                    "a".repeat(64))));
    var health =
        ExperimentDocumentHealthProjector.project(
            input(preview, false, DocumentHashes.sha256(DSL)), NOW);

    assertEquals(ExperimentHealthState.BLOCKED, health.state());
    assertEquals("UNVERIFIED_FOR_CURRENT_SNAPSHOT", health.facts().get("bindings.status"));
    assertTrue(
        health.findings().stream().anyMatch(item -> item.code().contains("asset-binding-missing")));
  }

  @Test
  void desktopContextRetainsOnlySemanticallyValidatedWorkflowWhenAnInvalidDraftIsEntered()
      throws Exception {
    var service = new ExperimentDocumentService(List.of());
    var session = new ExperimentDocumentSession(service);
    var original = preview(List.of());
    session.open(
        original, Path.of("saved.audioexp"), ExperimentDocumentSession.DirtyDecision.DISCARD, null);
    var node = new Node("duplicate.node", "test", "Duplicate", List.of(), List.of());
    var invalid =
        new Workflow(
            "experiment.workflow", "Invalid duplicate graph", List.of(node, node), List.of());
    session.editWorkflow(new WorkflowDslSerializer().serialize(invalid), session.revision());

    assertThrows(ExperimentDocumentException.class, session::validateDraft);
    var context = session.context();
    assertSame(original, context.preview());
    assertTrue(context.status().dirty());
    var health =
        ExperimentDocumentHealthProjector.project(
            new ExperimentDocumentHealthProjector.Input(
                context.preview(),
                context.status().path(),
                context.status().dirty(),
                context.revision(),
                context.preview().document().workflow().sha256(),
                List.of(),
                "No required sections"),
            NOW);
    assertEquals(original.canonicalSha256(), health.facts().get("document.sha256"));
    assertTrue(health.facts().get("document.hashState").contains("unsaved draft may differ"));
    assertTrue(
        health.findings().stream().anyMatch(finding -> finding.code().equals("document.dirty")));
  }

  private static ExperimentDocumentHealthProjector.Input input(
      ExperimentDocumentPreview preview, boolean dirty, String hash) {
    return new ExperimentDocumentHealthProjector.Input(
        preview,
        Path.of("experiment.audioexp"),
        dirty,
        7L,
        hash,
        List.of(),
        "No installed sections required");
  }

  private static ExperimentDocumentPreview preview(List<ExperimentDocument.AssetReference> assets)
      throws Exception {
    var document =
        new ExperimentDocument(
            ExperimentDocumentFormat.SCHEMA_URI,
            ExperimentDocumentFormat.FORMAT_ID,
            ExperimentDocumentFormat.VERSION,
            new ExperimentDocument.ExperimentInfo(
                "experiment.test",
                "Array experiment",
                "",
                List.of(),
                "analysis",
                "simulation",
                null,
                "0.1.0"),
            new ExperimentDocument.WorkflowPayload(
                ExperimentDocumentFormat.WORKFLOW_FORMAT_ID,
                ExperimentDocumentFormat.WORKFLOW_VERSION,
                DSL,
                DocumentHashes.sha256(DSL)),
            DocumentValue.object(Map.of()),
            List.of(),
            Map.of(),
            assets,
            List.of(),
            new ExperimentDocument.Provenance(
                "Test operator", "", NOW, NOW, "0.1.0", "", List.of()));
    return new ExperimentDocumentService(List.of())
        .preview(new ExperimentDocumentCodec().encode(document));
  }
}
