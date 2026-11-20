package org.hammer.audio.experiment.document.http;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.hammer.audio.experiment.document.ExperimentDocumentFormat;
import org.hammer.audio.experiment.document.ExperimentDocumentService;
import org.hammer.audio.experiment.document.ExperimentLocalBindingService;
import org.hammer.audio.experiment.document.ExperimentLocalBindings;
import org.hammer.audio.experiment.document.binding.ExperimentCaptureBindingResolver;
import org.hammer.audio.experiment.document.workspace.ExperimentDocumentWorkspaceService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Complete-document download, workspace copies and explicit local readiness operations. */
@RestController
@RequestMapping("/experiment-documents")
// The HTTP boundary coordinates portable DTOs, workspace lifecycle and local binding policy.
@SuppressWarnings("PMD.CouplingBetweenObjects")
public final class ExperimentDocumentSessionHttpAdapter {
  private final ExperimentDocumentWorkspaceService workspace;
  private final ExperimentDocumentService documents;
  private final ExperimentLocalResourcePolicy resources;

  /** Use the same document service and workspace for all import/export surfaces. */
  public ExperimentDocumentSessionHttpAdapter(
      ExperimentDocumentWorkspaceService workspace, ExperimentDocumentService documents) {
    this(workspace, documents, ExperimentLocalResourcePolicy.fromConfiguration(""));
  }

  /** Configure HTTP file access using directories explicitly approved by the host at startup. */
  @org.springframework.beans.factory.annotation.Autowired
  public ExperimentDocumentSessionHttpAdapter(
      ExperimentDocumentWorkspaceService workspace,
      ExperimentDocumentService documents,
      @org.springframework.beans.factory.annotation.Value(
              "${audio.experiment-document.local-roots:}")
          String approvedRoots) {
    this(workspace, documents, ExperimentLocalResourcePolicy.fromConfiguration(approvedRoots));
  }

  ExperimentDocumentSessionHttpAdapter(
      ExperimentDocumentWorkspaceService workspace,
      ExperimentDocumentService documents,
      ExperimentLocalResourcePolicy resources) {
    this.workspace = Objects.requireNonNull(workspace, "workspace");
    this.documents = Objects.requireNonNull(documents, "documents");
    this.resources = Objects.requireNonNull(resources, "resources");
  }

  /** Download the whole current document, including preserved profiles and plugin sections. */
  @GetMapping(path = "/current", produces = ExperimentDocumentFormat.MEDIA_TYPE)
  public ResponseEntity<byte[]> current() throws IOException {
    return attachment(workspace.exportCurrent(), "experiment.audioexp");
  }

  /** Return local document hash, dirty state and last saved/downloaded time. */
  @GetMapping(path = "/session", produces = MediaType.APPLICATION_JSON_VALUE)
  public org.hammer.audio.experiment.document.ExperimentDocumentSession.Status status()
      throws IOException {
    return workspace.status();
  }

  /** Acknowledge a downloaded snapshot only while its canonical hash remains current. */
  @PostMapping(path = "/saved")
  public ResponseEntity<Void> saved(@RequestHeader(HttpHeaders.IF_MATCH) String hash)
      throws IOException {
    workspace.downloaded(hash);
    return ResponseEntity.noContent().build();
  }

  /** Import a separate complete copy without touching current editable state. */
  @PostMapping(
      path = "/import",
      consumes = {ExperimentDocumentFormat.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
  public ExperimentDocumentWorkspaceService.CopyResult importCopy(
      HttpServletRequest request, @RequestHeader(HttpHeaders.IF_MATCH) String hash)
      throws IOException {
    return workspace.importCopy(request.getInputStream(), hash);
  }

  /** List explicitly imported copies; only names and hashes are exposed. */
  @GetMapping(path = "/copies")
  public List<ExperimentDocumentWorkspaceService.CopyResult> copies() {
    return workspace.copies();
  }

  /** Download a retained independent copy. */
  @GetMapping(path = "/copies/{id}", produces = ExperimentDocumentFormat.MEDIA_TYPE)
  public ResponseEntity<byte[]> copy(@PathVariable("id") String id) throws IOException {
    return attachment(workspace.exportCopy(id), "experiment-copy.audioexp");
  }

  /** Explicit discard of an independent copy. */
  @DeleteMapping(path = "/copies/{id}")
  public ResponseEntity<Void> removeCopy(@PathVariable("id") String id) {
    workspace.removeCopy(id);
    return ResponseEntity.noContent().build();
  }

  /**
   * Inspect only explicitly supplied local paths and selected device capabilities. Never opens a
   * device.
   */
  @PostMapping(path = "/bindings", consumes = MediaType.APPLICATION_JSON_VALUE)
  public BindingResponse bindings(HttpServletRequest input) throws IOException {
    BindingRequest request = ExperimentBindingRequestReader.read(input.getInputStream());
    org.hammer.audio.experiment.document.ExperimentDocumentPreview preview =
        documents.preview(request.document().getBytes(StandardCharsets.UTF_8));
    ExperimentLocalBindings selected = resolveBindings(preview.document(), request);
    org.hammer.audio.experiment.document.ExperimentLocalBindingPreview inspected =
        new ExperimentLocalBindingService().inspect(preview, selected);
    return new BindingResponse(inspected.ready(), inspected.diagnostics());
  }

  /**
   * Export selected assets/results separately from the portable setup, without server-side file
   * writes.
   */
  @PostMapping(
      path = "/evidence",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = "application/zip")
  public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
      evidence(HttpServletRequest input) throws IOException {
    BindingRequest request = ExperimentBindingRequestReader.read(input.getInputStream());
    org.hammer.audio.experiment.document.ExperimentDocumentPreview preview =
        documents.preview(request.document().getBytes(StandardCharsets.UTF_8));
    ExperimentLocalBindings selected = resolveBindings(preview.document(), request);
    org.hammer.audio.experiment.document.ExperimentEvidenceExporter exporter =
        new org.hammer.audio.experiment.document.ExperimentEvidenceExporter();
    exporter.validate(preview, selected);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/zip"))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"experiment-evidence.zip\"")
        .body(output -> exporter.write(output, preview, selected));
  }

  /** Return structured errors for explicit session and binding operations. */
  @org.springframework.web.bind.annotation.ExceptionHandler({
    IOException.class,
    IllegalArgumentException.class,
    IllegalStateException.class,
    SecurityException.class
  })
  public ResponseEntity<ExperimentDocumentHttpAdapter.ErrorResponse> failure(Exception exception) {
    if (exception instanceof SecurityException) {
      return ResponseEntity.status(403)
          .body(
              new ExperimentDocumentHttpAdapter.ErrorResponse(
                  "local-resource-not-approved", "/localBindings", exception.getMessage()));
    }
    if (exception
        instanceof org.hammer.audio.experiment.document.ExperimentDocumentException document) {
      return ResponseEntity.status(422)
          .body(
              new ExperimentDocumentHttpAdapter.ErrorResponse(
                  document.code(), document.pointer(), document.getMessage()));
    }
    if (exception
        instanceof
        org.hammer.audio.experiment.document.workspace.ExperimentDocumentApplyException apply) {
      return ResponseEntity.status(409)
          .body(
              new ExperimentDocumentHttpAdapter.ErrorResponse(
                  apply.code(), "/", apply.getMessage()));
    }
    return ResponseEntity.badRequest()
        .body(
            new ExperimentDocumentHttpAdapter.ErrorResponse(
                "document-operation",
                "/localBindings",
                exception.getMessage() == null
                    ? "Document operation failed"
                    : exception.getMessage()));
  }

  // Each request owns this sorted map; no mutable request state is shared between threads.
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  private ExperimentLocalBindings resolveBindings(
      org.hammer.audio.experiment.document.ExperimentDocument document, BindingRequest request)
      throws IOException {
    if (request.assetPaths().size() > 64) {
      throw new IllegalArgumentException("At most 64 asset bindings are allowed");
    }
    Map<String, Path> assets = new TreeMap<>();
    request
        .assetPaths()
        .forEach(
            (id, path) -> {
              if (path != null && !path.isBlank()) {
                assets.put(id, Path.of(path));
              }
            });
    Path output =
        request.outputDirectory() == null || request.outputDirectory().isBlank()
            ? null
            : Path.of(request.outputDirectory());
    Map<String, Path> approvedAssets = resources.assets(assets);
    Path approvedOutput = resources.outputDirectory(output);
    org.hammer.audio.experiment.document.ExperimentCaptureBinding capture =
        new ExperimentCaptureBindingResolver()
            .resolve(
                document,
                request.deviceId(),
                request.deviceChannels(),
                request.channelMapping(),
                request.synchronizationConfirmed(),
                request.calibrationConfirmed());
    return new ExperimentLocalBindings(
        approvedAssets, approvedOutput, capture, request.confirmedMediaTypes());
  }

  private static ResponseEntity<byte[]> attachment(byte[] bytes, String filename) {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(ExperimentDocumentFormat.MEDIA_TYPE))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
        .body(bytes);
  }

  /**
   * Explicit local bindings; this request is never serialized into a portable document.
   *
   * @param document complete portable document JSON
   * @param assetPaths explicitly chosen local asset paths by asset id
   * @param outputDirectory explicitly chosen local output directory
   * @param deviceId explicitly chosen local device identity
   * @param deviceChannels physical capture channels
   * @param channelMapping unique physical index for each logical channel
   * @param synchronizationConfirmed whether synchronized capture was verified
   * @param calibrationConfirmed whether calibration applicability was confirmed
   * @param confirmedMediaTypes explicit confirmations for unrecognized asset formats
   */
  public record BindingRequest(
      String document,
      Map<String, String> assetPaths,
      String outputDirectory,
      String deviceId,
      int deviceChannels,
      List<Integer> channelMapping,
      boolean synchronizationConfirmed,
      boolean calibrationConfirmed,
      Map<String, String> confirmedMediaTypes) {
    /* Bound and copy the request before local resource inspection. */
    public BindingRequest {
      if (document == null || document.length() > ExperimentDocumentFormat.MAX_DOCUMENT_BYTES) {
        throw new IllegalArgumentException("Document is missing or oversized");
      }
      if (assetPaths != null && assetPaths.size() > 64
          || confirmedMediaTypes != null && confirmedMediaTypes.size() > 64
          || channelMapping != null && channelMapping.size() > 256) {
        throw new IllegalArgumentException("Local binding request exceeds collection limits");
      }
      assetPaths = Map.copyOf(assetPaths == null ? Map.of() : assetPaths);
      for (Map.Entry<String, String> asset : assetPaths.entrySet()) {
        if (asset.getKey().length() > 1024 || asset.getValue().length() > 4096) {
          throw new IllegalArgumentException("Local asset binding exceeds path limits");
        }
      }
      if (outputDirectory != null && outputDirectory.length() > 4096
          || deviceId != null && deviceId.length() > 4096) {
        throw new IllegalArgumentException("Local resource identifier is too long");
      }
      channelMapping = List.copyOf(channelMapping == null ? List.of() : channelMapping);
      confirmedMediaTypes =
          Map.copyOf(confirmedMediaTypes == null ? Map.of() : confirmedMediaTypes);
    }
  }

  /**
   * Resource readiness diagnostics without echoing local file paths into shared preview data.
   *
   * @param ready whether all document and local resource requirements are satisfied
   * @param diagnostics bounded pointer-aware readiness messages
   */
  public record BindingResponse(
      boolean ready, List<org.hammer.audio.plugin.document.DocumentDiagnostic> diagnostics) {
    /* Defensively copy the inspection result. */
    public BindingResponse {
      diagnostics = List.copyOf(diagnostics);
    }
  }
}
