package org.hammer.audio.experiment.document.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hammer.audio.experiment.document.DocumentHashes;
import org.hammer.audio.experiment.document.ExperimentDocument;
import org.hammer.audio.experiment.document.ExperimentDocumentCodec;
import org.hammer.audio.experiment.document.ExperimentDocumentFormat;
import org.hammer.audio.experiment.document.ExperimentDocumentService;
import org.hammer.audio.plugin.document.DocumentValue;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperationLog;
import org.hammer.audio.workflow.WorkflowValidator;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.editor.DirtyWorkflowException;
import org.hammer.audio.workflow.editor.WorkflowEditorService;
import org.junit.jupiter.api.Test;

class ExperimentDocumentWorkspaceServiceTest {

  private static final String WORKFLOW =
      "workflow\n  id: imported.workflow\n  name: Imported workflow\n  nodes:\n  edges:\n";

  private final ExperimentDocumentCodec codec = new ExperimentDocumentCodec();
  private final ExperimentDocumentService documentService =
      new ExperimentDocumentService(List.of());

  @Test
  void readinessSnapshotKeepsDocumentAndWorkflowCorrelatedAndDoesNotCreateDocuments()
      throws Exception {
    WorkflowEditorService editor = editor();
    ExperimentDocumentWorkspaceService workspace =
        new ExperimentDocumentWorkspaceService(documentService, editor);
    var empty = workspace.readinessContext();
    assertFalse(empty.document().status().open());
    assertNull(empty.document().preview());
    assertEquals(editor.currentWorkflow(), empty.workflow());
    assertFalse(empty.workflowDirty());

    byte[] source = codec.encode(document(Map.of()));
    workspace.apply(stream(source), documentService.preview(source).canonicalSha256(), false);
    var before = workspace.readinessContext();
    Workflow changed = new Workflow("changed", "Changed", List.of(), List.of());
    editor.importGraph(changed);
    var after = workspace.readinessContext();
    assertEquals(changed, after.workflow());
    assertEquals(changed, documentService.workflow(after.document().preview()));
    assertEquals(after.document().preview().document().workflow().sha256(), after.workflowSha256());
    assertTrue(after.workflowDirty());
    assertTrue(after.document().status().dirty());
    assertTrue(after.document().revision() > before.document().revision());
    assertEquals("imported.workflow", before.workflow().id());
    assertEquals(before.workflow(), documentService.workflow(before.document().preview()));
    assertFalse(empty.document().status().open());
  }

  @Test
  void completeDocumentIsRetainedAcrossWorkflowEditAndExport() throws Exception {
    byte[] source = codec.encode(document(Map.of()));
    WorkflowEditorService editor = editor();
    ExperimentDocumentWorkspaceService workspace =
        new ExperimentDocumentWorkspaceService(documentService, editor);
    var preview = documentService.preview(source);
    workspace.apply(stream(source), preview.canonicalSha256(), false);
    Workflow imported = editor.currentWorkflow();
    editor.importGraph(
        new Workflow(
            imported.id(), "Edited name", imported.nodes(), imported.edges(), imported.metadata()));
    ExperimentDocument exported = codec.decode(workspace.exportCurrent());
    assertEquals(preview.document().experiment(), exported.experiment());
    assertEquals(preview.document().profiles(), exported.profiles());
    assertEquals(preview.document().outputs(), exported.outputs());
    assertEquals(
        "Edited name",
        documentService.workflow(documentService.preview(workspace.exportCurrent())).name());
  }

  @Test
  void semanticPreviewPreconditionRejectsLaterGraphChanges() throws Exception {
    byte[] source = codec.encode(document(Map.of()));
    WorkflowEditorService editor = editor();
    ExperimentDocumentWorkspaceService workspace =
        new ExperimentDocumentWorkspaceService(documentService, editor);
    var preview = documentService.preview(source);
    var comparison = workspace.comparison(preview);
    assertFalse(comparison.changes().isEmpty());
    editor.importGraph(new Workflow("new-current", "Changed", List.of(), List.of()));
    var failure =
        assertThrows(
            ExperimentDocumentApplyException.class,
            () ->
                workspace.apply(
                    stream(source),
                    preview.canonicalSha256(),
                    true,
                    comparison.currentWorkflowSha256()));
    assertEquals("current-workflow-changed", failure.code());
    assertEquals("new-current", editor.currentWorkflow().id());
  }

  @Test
  void independentImportCopyPreservesCurrentDirtyState() throws Exception {
    byte[] source = codec.encode(document(Map.of()));
    WorkflowEditorService editor = editor();
    editor.importGraph(new Workflow("dirty-current", "Keep me", List.of(), List.of()));
    ExperimentDocumentWorkspaceService workspace =
        new ExperimentDocumentWorkspaceService(documentService, editor);
    var copy =
        workspace.importCopy(stream(source), documentService.preview(source).canonicalSha256());
    assertEquals("dirty-current", editor.currentWorkflow().id());
    assertTrue(editor.isDirty());
    assertEquals(
        documentService.preview(source).document(),
        documentService.preview(workspace.exportCopy(copy.id())).document());
    assertEquals(1, workspace.copies().size());
  }

  @Test
  void requiredUnknownPluginCanBeCopiedWithoutInterpretingOrApplyingIt() throws Exception {
    ExperimentDocument.PluginSection section =
        new ExperimentDocument.PluginSection(
            7,
            "future/7",
            DocumentValue.object(Map.of("opaque", DocumentValue.string("preserve exactly"))));
    ExperimentDocument optional = document(Map.of("missing", Map.of("settings", section)));
    ExperimentDocument required =
        new ExperimentDocument(
            optional.schema(),
            optional.format(),
            optional.formatVersion(),
            optional.experiment(),
            optional.workflow(),
            optional.profiles(),
            List.of(
                new ExperimentDocument.PluginRequirement("missing", "[2,3)", List.of("settings"))),
            optional.pluginData(),
            optional.assets(),
            optional.outputs(),
            optional.provenance());
    byte[] source = codec.encode(required);
    WorkflowEditorService editor = editor();
    ExperimentDocumentWorkspaceService workspace =
        new ExperimentDocumentWorkspaceService(documentService, editor);
    String hash = documentService.preview(source).canonicalSha256();
    ExperimentDocumentWorkspaceService.CopyResult copy = workspace.importCopy(stream(source), hash);
    assertTrue(copy.readOnly());
    org.junit.jupiter.api.Assertions.assertArrayEquals(source, workspace.exportCopy(copy.id()));
    assertEquals("current.workflow", editor.currentWorkflow().id());
    assertFalse(editor.isDirty());
    assertThrows(
        ExperimentDocumentApplyException.class, () -> workspace.apply(stream(source), hash, false));
  }

  @Test
  void acknowledgedDownloadRemovesDocumentDirtyPromptButLaterEditsRestoreIt() throws Exception {
    byte[] source = codec.encode(document(Map.of()));
    WorkflowEditorService editor = editor();
    ExperimentDocumentWorkspaceService workspace =
        new ExperimentDocumentWorkspaceService(documentService, editor);
    var preview = documentService.preview(source);
    workspace.apply(stream(source), preview.canonicalSha256(), false);
    assertTrue(workspace.comparison(preview).dirty());
    workspace.downloaded(documentService.preview(workspace.exportCurrent()).canonicalSha256());
    assertFalse(workspace.comparison(preview).dirty());
    workspace.apply(stream(source), preview.canonicalSha256(), false);
    workspace.downloaded(documentService.preview(workspace.exportCurrent()).canonicalSha256());
    editor.importGraph(new Workflow("edited", "Changed", List.of(), List.of()));
    assertTrue(workspace.comparison(preview).dirty());
  }

  @Test
  void correctQuotedPreviewHashImportsAndMarksWorkflowDirty() throws Exception {
    byte[] source = codec.encode(document(Map.of()));
    String hash = documentService.preview(source).canonicalSha256();
    WorkflowEditorService editor = editor();
    ExperimentDocumentWorkspaceService workspace =
        new ExperimentDocumentWorkspaceService(documentService, editor);

    var result = workspace.apply(stream(source), "\"" + hash + "\"", false);

    assertEquals(hash, result.canonicalSha256());
    assertEquals("imported.workflow", result.projection().workflowId());
    assertTrue(result.dirty());
    assertTrue(editor.isDirty());
  }

  @Test
  void mismatchedHashRejectsWithoutMutatingWorkflow() throws Exception {
    byte[] source = codec.encode(document(Map.of()));
    WorkflowEditorService editor = editor();
    ExperimentDocumentWorkspaceService workspace =
        new ExperimentDocumentWorkspaceService(documentService, editor);

    ExperimentDocumentApplyException failure =
        assertThrows(
            ExperimentDocumentApplyException.class,
            () -> workspace.apply(stream(source), "f".repeat(64), false));

    assertEquals("document-hash-mismatch", failure.code());
    assertEquals("current.workflow", editor.currentProjection().workflowId());
    assertFalse(editor.isDirty());
  }

  @Test
  void dirtyWorkflowRequiresExplicitDiscardAndThenImportsAtomically() throws Exception {
    byte[] source = codec.encode(document(Map.of()));
    String hash = documentService.preview(source).canonicalSha256();
    WorkflowEditorService editor = editor();
    editor.importGraph(new Workflow("dirty.workflow", "Dirty", List.of(), List.of()));
    ExperimentDocumentWorkspaceService workspace =
        new ExperimentDocumentWorkspaceService(documentService, editor);

    assertThrows(DirtyWorkflowException.class, () -> workspace.apply(stream(source), hash, false));
    assertEquals("dirty.workflow", editor.currentProjection().workflowId());

    workspace.apply(stream(source), hash, true);
    assertEquals("imported.workflow", editor.currentProjection().workflowId());
    assertTrue(editor.isDirty());
  }

  @Test
  void unknownOptionalPluginRemainsReadOnlyAndCannotBeApplied() throws Exception {
    ExperimentDocument.PluginSection section =
        new ExperimentDocument.PluginSection(
            1, "example/1", DocumentValue.object(Map.of("enabled", DocumentValue.bool(true))));
    byte[] source = codec.encode(document(Map.of("not-installed", Map.of("advisory", section))));
    String hash = documentService.preview(source).canonicalSha256();
    WorkflowEditorService editor = editor();
    ExperimentDocumentWorkspaceService workspace =
        new ExperimentDocumentWorkspaceService(documentService, editor);

    ExperimentDocumentApplyException failure =
        assertThrows(
            ExperimentDocumentApplyException.class,
            () -> workspace.apply(stream(source), hash, false));

    assertEquals("document-read-only", failure.code());
    assertEquals("current.workflow", editor.currentProjection().workflowId());
  }

  @Test
  void activeCollaborationBlocksApplyButRetainedEmptySessionDoesNot() throws Exception {
    byte[] source = codec.encode(document(Map.of()));
    String hash = documentService.preview(source).canonicalSha256();
    WorkflowEditorService editor = editor();
    WorkflowSessionRegistry registry = new WorkflowSessionRegistry();
    OperationActor owner = new OperationActor("actor.owner", "user.owner", "Owner");
    registry.create(
        "shared-session",
        CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
        owner,
        new Workflow("shared.workflow", "Shared", List.of(), List.of()));
    ExperimentDocumentWorkspaceService workspace =
        new ExperimentDocumentWorkspaceService(documentService, editor, registry);

    ExperimentDocumentApplyException failure =
        assertThrows(
            ExperimentDocumentApplyException.class,
            () -> workspace.apply(stream(source), hash, false));
    assertEquals("collaboration-active", failure.code());
    assertEquals("current.workflow", editor.currentProjection().workflowId());

    registry.leave("shared-session", owner.actorId());
    workspace.apply(stream(source), hash, false);
    assertEquals("imported.workflow", editor.currentProjection().workflowId());
  }

  private static WorkflowEditorService editor() {
    Workflow current = new Workflow("current.workflow", "Current", List.of(), List.of());
    return new WorkflowEditorService(new WorkflowOperationLog(current), new WorkflowValidator());
  }

  private static ByteArrayInputStream stream(byte[] source) {
    return new ByteArrayInputStream(source);
  }

  private static ExperimentDocument document(
      Map<String, Map<String, ExperimentDocument.PluginSection>> pluginData) {
    return new ExperimentDocument(
        ExperimentDocumentFormat.SCHEMA_RESOURCE,
        ExperimentDocumentFormat.FORMAT_ID,
        ExperimentDocumentFormat.VERSION,
        new ExperimentDocument.ExperimentInfo(
            "imported.experiment",
            "Imported experiment",
            "Workspace apply fixture",
            List.of(),
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
        pluginData,
        List.of(),
        List.of(),
        new ExperimentDocument.Provenance(
            "Workspace Test",
            "",
            Instant.parse("2026-07-23T12:00:00Z"),
            Instant.parse("2026-07-23T12:00:00Z"),
            "0.0.4-SNAPSHOT",
            "",
            List.of()));
  }
}
