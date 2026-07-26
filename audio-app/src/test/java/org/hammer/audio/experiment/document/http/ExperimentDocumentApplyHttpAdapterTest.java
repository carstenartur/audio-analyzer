package org.hammer.audio.experiment.document.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hammer.audio.experiment.document.DocumentHashes;
import org.hammer.audio.experiment.document.ExperimentDocument;
import org.hammer.audio.experiment.document.ExperimentDocumentCodec;
import org.hammer.audio.experiment.document.ExperimentDocumentFormat;
import org.hammer.audio.experiment.document.ExperimentDocumentService;
import org.hammer.audio.experiment.document.workspace.ExperimentDocumentApplyException;
import org.hammer.audio.experiment.document.workspace.ExperimentDocumentWorkspaceService;
import org.hammer.audio.plugin.document.DocumentValue;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperationLog;
import org.hammer.audio.workflow.WorkflowValidator;
import org.hammer.audio.workflow.editor.DirtyWorkflowException;
import org.hammer.audio.workflow.editor.WorkflowEditorService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class ExperimentDocumentApplyHttpAdapterTest {

  private static final String WORKFLOW =
      "workflow\n  id: apply.workflow\n  name: Apply workflow\n  nodes:\n  edges:\n";

  private final ExperimentDocumentCodec codec = new ExperimentDocumentCodec();
  private final ExperimentDocumentService documentService =
      new ExperimentDocumentService(List.of());

  @Test
  void applyEchoesCanonicalEtagAndReturnsDirtyImportedProjection() throws Exception {
    byte[] source = codec.encode(document());
    String hash = documentService.preview(source).canonicalSha256();
    WorkflowEditorService editor = editor();
    ExperimentDocumentApplyHttpAdapter adapter = adapter(editor);

    var response = adapter.apply(request(source), "\"" + hash + "\"", false);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("\"" + hash + "\"", response.getHeaders().getFirst(HttpHeaders.ETAG));
    assertEquals(hash, response.getBody().canonicalSha256());
    assertEquals("apply.workflow", response.getBody().projection().workflowId());
    assertTrue(response.getBody().dirty());
    assertTrue(editor.isDirty());
  }

  @Test
  void incorrectHashIsPreconditionFailedAndDoesNotApply() throws Exception {
    byte[] source = codec.encode(document());
    WorkflowEditorService editor = editor();
    ExperimentDocumentApplyHttpAdapter adapter = adapter(editor);

    ExperimentDocumentApplyException failure =
        assertThrows(
            ExperimentDocumentApplyException.class,
            () -> adapter.apply(request(source), "0".repeat(64), false));
    var response = adapter.handleApplyFailure(failure);

    assertEquals(HttpStatus.PRECONDITION_FAILED, response.getStatusCode());
    assertEquals("document-hash-mismatch", response.getBody().code());
    assertEquals("current.workflow", editor.currentProjection().workflowId());
  }

  @Test
  void dirtyAndMalformedHashFailuresUseStableHttpStatusCodes() {
    ExperimentDocumentApplyHttpAdapter adapter = adapter(editor());

    assertEquals(
        HttpStatus.CONFLICT, adapter.handleDirty(new DirtyWorkflowException()).getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST,
        adapter
            .handleApplyFailure(
                new ExperimentDocumentApplyException("invalid-document-hash", "Invalid If-Match"))
            .getStatusCode());
    assertEquals(
        HttpStatus.CONFLICT,
        adapter
            .handleApplyFailure(
                new ExperimentDocumentApplyException("document-read-only", "Read-only document"))
            .getStatusCode());
  }

  private ExperimentDocumentApplyHttpAdapter adapter(WorkflowEditorService editor) {
    ExperimentDocumentWorkspaceService workspace =
        new ExperimentDocumentWorkspaceService(documentService, editor);
    return new ExperimentDocumentApplyHttpAdapter(workspace);
  }

  private static WorkflowEditorService editor() {
    Workflow workflow = new Workflow("current.workflow", "Current", List.of(), List.of());
    return new WorkflowEditorService(new WorkflowOperationLog(workflow), new WorkflowValidator());
  }

  private static MockHttpServletRequest request(byte[] source) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContentType(ExperimentDocumentFormat.MEDIA_TYPE);
    request.setContent(source);
    return request;
  }

  private static ExperimentDocument document() {
    return new ExperimentDocument(
        ExperimentDocumentFormat.SCHEMA_RESOURCE,
        ExperimentDocumentFormat.FORMAT_ID,
        ExperimentDocumentFormat.VERSION,
        new ExperimentDocument.ExperimentInfo(
            "apply.experiment",
            "Apply experiment",
            "Apply fixture",
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
        Map.of(),
        List.of(),
        List.of(),
        new ExperimentDocument.Provenance(
            "Apply Test",
            "",
            Instant.parse("2026-07-23T12:00:00Z"),
            Instant.parse("2026-07-23T12:00:00Z"),
            "0.0.4-SNAPSHOT",
            "",
            List.of()));
  }
}
