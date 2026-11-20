package org.hammer.audio.experiment.document.http;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Objects;
import org.hammer.audio.experiment.document.ExperimentDocumentException;
import org.hammer.audio.experiment.document.ExperimentDocumentFormat;
import org.hammer.audio.experiment.document.workspace.ExperimentDocumentApplyException;
import org.hammer.audio.experiment.document.workspace.ExperimentDocumentWorkspaceService;
import org.hammer.audio.workflow.editor.DirtyWorkflowException;
import org.hammer.audio.workflow.editor.WorkflowProjection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Confirmed destructive apply adapter for portable experiment documents. */
@RestController
@RequestMapping("/experiment-documents")
public final class ExperimentDocumentApplyHttpAdapter {

  /** Explicit confirmation header for discarding dirty current workflow state. */
  public static final String CURRENT_WORKFLOW_HEADER = "X-Audio-Analyzer-Current-Workflow";

  /** Explicit dirty-workflow discard decision. */
  public static final String DISCARD_DIRTY_HEADER = "X-Audio-Analyzer-Discard-Dirty";

  private final ExperimentDocumentWorkspaceService workspaceService;

  /** Create the adapter with the server-authoritative workspace coordinator. */
  public ExperimentDocumentApplyHttpAdapter(ExperimentDocumentWorkspaceService workspaceService) {
    this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
  }

  /** Compatibility overload used by non-HTTP callers. */
  public ResponseEntity<ApplyResponse> apply(
      HttpServletRequest request, String expectedCanonicalSha256, boolean discardDirty)
      throws IOException {
    return apply(request, expectedCanonicalSha256, discardDirty, null);
  }

  /** Revalidate both preview hashes; replacement also requires an explicit dirty-state decision. */
  @PostMapping(
      path = "/apply",
      consumes = {ExperimentDocumentFormat.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE},
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApplyResponse> apply(
      HttpServletRequest request,
      @RequestHeader(HttpHeaders.IF_MATCH) String expectedCanonicalSha256,
      @RequestHeader(name = DISCARD_DIRTY_HEADER, defaultValue = "false") boolean discardDirty,
      @RequestHeader(name = CURRENT_WORKFLOW_HEADER) String currentWorkflowHash)
      throws IOException {
    ExperimentDocumentWorkspaceService.ApplyResult result =
        workspaceService.apply(
            request.getInputStream(), expectedCanonicalSha256, discardDirty, currentWorkflowHash);
    return ResponseEntity.ok()
        .header(HttpHeaders.ETAG, "\"" + result.canonicalSha256() + "\"")
        .body(new ApplyResponse(result.canonicalSha256(), result.projection(), result.dirty()));
  }

  /** Reject a concurrently changed graph instead of silently replacing a different preview. */
  @ExceptionHandler(org.hammer.audio.workflow.editor.StaleWorkflowException.class)
  public ResponseEntity<ExperimentDocumentHttpAdapter.ErrorResponse> handleStale(
      org.hammer.audio.workflow.editor.StaleWorkflowException failure) {
    return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
        .body(
            new ExperimentDocumentHttpAdapter.ErrorResponse(
                "current-workflow-changed", "/workflow", failure.getMessage()));
  }

  /** Dirty workspace conflicts require an explicit discard confirmation. */
  @ExceptionHandler(DirtyWorkflowException.class)
  public ResponseEntity<ExperimentDocumentHttpAdapter.ErrorResponse> handleDirty(
      DirtyWorkflowException failure) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new ExperimentDocumentHttpAdapter.ErrorResponse(
                "dirty-workflow", "/workflow", failure.getMessage()));
  }

  /** Map stable apply precondition/compatibility failures to appropriate HTTP status codes. */
  @ExceptionHandler(ExperimentDocumentApplyException.class)
  public ResponseEntity<ExperimentDocumentHttpAdapter.ErrorResponse> handleApplyFailure(
      ExperimentDocumentApplyException failure) {
    HttpStatus status =
        switch (failure.code()) {
          case "document-hash-mismatch", "current-workflow-changed" ->
              HttpStatus.PRECONDITION_FAILED;
          case "invalid-document-hash" -> HttpStatus.BAD_REQUEST;
          default -> HttpStatus.CONFLICT;
        };
    return ResponseEntity.status(status)
        .body(
            new ExperimentDocumentHttpAdapter.ErrorResponse(
                failure.code(), "/", failure.getMessage()));
  }

  /** Pointer-aware malformed document response. */
  @ExceptionHandler(ExperimentDocumentException.class)
  public ResponseEntity<ExperimentDocumentHttpAdapter.ErrorResponse> handleDocumentFailure(
      ExperimentDocumentException failure) {
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .body(
            new ExperimentDocumentHttpAdapter.ErrorResponse(
                failure.code(), failure.pointer(), failure.getMessage()));
  }

  /** Bounded transport/read failures. */
  @ExceptionHandler(IOException.class)
  public ResponseEntity<ExperimentDocumentHttpAdapter.ErrorResponse> handleInputFailure(
      IOException failure) {
    return ResponseEntity.badRequest()
        .body(
            new ExperimentDocumentHttpAdapter.ErrorResponse(
                "document-input", "/", failure.getMessage()));
  }

  /**
   * Successful confirmed apply response.
   *
   * @param canonicalSha256 canonical imported document digest
   * @param projection resulting server-authoritative workflow projection
   * @param dirty whether the imported workflow has uncheckpointed changes
   */
  public record ApplyResponse(
      String canonicalSha256, WorkflowProjection projection, boolean dirty) {

    /* Validate immutable response fields. */
    public ApplyResponse {
      Objects.requireNonNull(canonicalSha256, "canonicalSha256");
      Objects.requireNonNull(projection, "projection");
    }
  }
}
