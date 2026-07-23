package org.hammer.audio.experiment.document.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
  private final ExperimentDocumentService documentService = new ExperimentDocumentService(List.of());

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

    assertThrows(
        DirtyWorkflowException.class, () -> workspace.apply(stream(source), hash, false));
    assertEquals("dirty.workflow", editor.currentProjection().workflowId());

    workspace.apply(stream(source), hash, true);
    assertEquals("imported.workflow", editor.currentProjection().workflowId());
    assertTrue(editor.isDirty());
  }

  @Test
  void unknownOptionalPluginRemainsReadOnlyAndCannotBeApplied() throws Exception {
    ExperimentDocument.PluginSection section =
        new ExperimentDocument.PluginSection(
            1,
            "example/1",
            DocumentValue.object(Map.of("enabled", DocumentValue.bool(true))));
    byte[] source =
        codec.encode(document(Map.of("not-installed", Map.of("advisory", section))));
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
    return new WorkflowEditorService(
        new WorkflowOperationLog(current), new WorkflowValidator());
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
