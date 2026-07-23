package org.hammer.audio.experiment.document.http;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.experiment.document.ExperimentDocument;
import org.hammer.audio.experiment.document.ExperimentDocumentException;
import org.hammer.audio.experiment.document.ExperimentDocumentFormat;
import org.hammer.audio.experiment.document.ExperimentDocumentPreview;
import org.hammer.audio.experiment.document.ExperimentDocumentService;
import org.hammer.audio.plugin.document.DocumentDiagnostic;
import org.hammer.audio.workflow.Workflow;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Thin Spring MVC adapter for safe portable experiment-document inspection and normalization. */
@RestController
@RequestMapping("/experiment-documents")
public final class ExperimentDocumentHttpAdapter {

  private static final String SCHEMA_MEDIA_TYPE = "application/schema+json";
  private static final String NORMALIZED_FILENAME = "normalized.audioexp";

  private final ExperimentDocumentService documentService;

  /** Create the adapter with the shared application document service. */
  public ExperimentDocumentHttpAdapter(ExperimentDocumentService documentService) {
    this.documentService = Objects.requireNonNull(documentService, "documentService");
  }

  /** Parse and resolve one untrusted document without mutating the current workflow. */
  @PostMapping(
      path = "/preview",
      consumes = {ExperimentDocumentFormat.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE},
      produces = MediaType.APPLICATION_JSON_VALUE)
  public PreviewResponse preview(HttpServletRequest request) throws IOException {
    ExperimentDocumentPreview preview = documentService.preview(request.getInputStream());
    Workflow workflow = documentService.workflow(preview);
    ExperimentDocument.ExperimentInfo experiment = preview.document().experiment();
    return new PreviewResponse(
        preview.document().format(),
        preview.document().formatVersion(),
        experiment.id(),
        experiment.name(),
        experiment.sourceMode(),
        preview.canonicalSha256(),
        workflow.id(),
        workflow.name(),
        workflow.nodes().size(),
        workflow.edges().size(),
        preview.document().requiredPlugins(),
        preview.diagnostics(),
        preview.migrations(),
        preview.executionAllowed(),
        preview.readOnly());
  }

  /** Return canonical normalized bytes without applying or executing the imported document. */
  @PostMapping(
      path = "/normalize",
      consumes = {ExperimentDocumentFormat.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE},
      produces = ExperimentDocumentFormat.MEDIA_TYPE)
  public ResponseEntity<byte[]> normalize(HttpServletRequest request) throws IOException {
    byte[] bytes = documentService.normalize(request.getInputStream());
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(ExperimentDocumentFormat.MEDIA_TYPE))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + NORMALIZED_FILENAME + "\"")
        .body(bytes);
  }

  /** Return the bundled public v1 schema without dereferencing its identifier. */
  @GetMapping(path = "/schema", produces = SCHEMA_MEDIA_TYPE)
  public ResponseEntity<byte[]> schema() throws IOException {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(SCHEMA_MEDIA_TYPE))
        .body(documentService.schemaBytes());
  }

  /** Return pointer-aware validation failures as an unprocessable document response. */
  @ExceptionHandler(ExperimentDocumentException.class)
  public ResponseEntity<ErrorResponse> handleDocumentFailure(ExperimentDocumentException failure) {
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .body(new ErrorResponse(failure.code(), failure.pointer(), failure.getMessage()));
  }

  /** Return bounded input or transport failures without exposing implementation details. */
  @ExceptionHandler(IOException.class)
  public ResponseEntity<ErrorResponse> handleInputFailure(IOException failure) {
    return ResponseEntity.badRequest()
        .body(new ErrorResponse("document-input", "/", failure.getMessage()));
  }

  /** Safe preview response used by browser, CLI tooling and API clients. */
  public record PreviewResponse(
      String format,
      int formatVersion,
      String experimentId,
      String experimentName,
      String sourceMode,
      String canonicalSha256,
      String workflowId,
      String workflowName,
      int nodeCount,
      int edgeCount,
      List<ExperimentDocument.PluginRequirement> requiredPlugins,
      List<DocumentDiagnostic> diagnostics,
      List<String> migrations,
      boolean executionAllowed,
      boolean readOnly) {

    /** Defensively copy response collections. */
    public PreviewResponse {
      requiredPlugins = List.copyOf(Objects.requireNonNull(requiredPlugins, "requiredPlugins"));
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      migrations = List.copyOf(Objects.requireNonNull(migrations, "migrations"));
    }
  }

  /** Stable pointer-aware REST error response. */
  public record ErrorResponse(String code, String pointer, String message) {

    /** Validate required error fields. */
    public ErrorResponse {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(pointer, "pointer");
      Objects.requireNonNull(message, "message");
    }
  }
}
