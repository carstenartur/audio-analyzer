package org.hammer.audio.experiment.document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.hammer.audio.plugin.document.DocumentDiagnostic;

/** Verifies portable asset/output requirements against explicitly selected local resources. */
@SuppressWarnings("PMD.LooseCoupling")
public final class ExperimentLocalBindingService {

  private final java.time.Clock clock;

  /** Use the current time when assessing calibration validity. */
  public ExperimentLocalBindingService() {
    this(java.time.Clock.systemUTC());
  }

  /** Supply a clock for deterministic calibration validity checks. */
  public ExperimentLocalBindingService(java.time.Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Inspect local bindings without modifying the document, selected files or output directory.
   *
   * <p>Asset hashes are streamed and may be expensive; callers must not invoke this method on a UI
   * or realtime thread.
   */
  public ExperimentLocalBindingPreview inspect(
      ExperimentDocumentPreview documentPreview, ExperimentLocalBindings bindings) {
    Objects.requireNonNull(documentPreview, "documentPreview");
    Objects.requireNonNull(bindings, "bindings");
    ArrayList<DocumentDiagnostic> diagnostics = new ArrayList<>();
    Set<String> requiredAssetIds = new HashSet<>();
    for (int index = 0; index < documentPreview.document().assets().size(); index++) {
      ExperimentDocument.AssetReference asset = documentPreview.document().assets().get(index);
      requiredAssetIds.add(asset.id());
      inspectAsset(asset, index, bindings, diagnostics);
    }
    bindings.assetPaths().keySet().stream()
        .filter(assetId -> !requiredAssetIds.contains(assetId))
        .forEach(
            assetId ->
                diagnostics.add(
                    warning(
                        "/localBindings/assets/" + escape(assetId),
                        "unused-asset-binding",
                        "Local asset binding does not match a document requirement")));
    inspectOutputDirectory(documentPreview.document(), bindings, diagnostics);
    ExperimentCaptureReadiness.inspect(
        documentPreview.document(), bindings.capture(), clock.instant(), diagnostics);
    boolean hasErrors =
        diagnostics.stream().anyMatch(item -> item.severity() == DocumentDiagnostic.Severity.ERROR);
    boolean ready = !hasErrors && documentPreview.executionAllowed() && !documentPreview.readOnly();
    return new ExperimentLocalBindingPreview(bindings, diagnostics, ready);
  }

  private static void inspectAsset(
      ExperimentDocument.AssetReference asset,
      int index,
      ExperimentLocalBindings bindings,
      ArrayList<DocumentDiagnostic> diagnostics) {
    String pointer = "/assets/" + index;
    Path local = bindings.assetPath(asset.id()).orElse(null);
    if (local == null) {
      diagnostics.add(
          error(
              pointer + "/localBinding",
              "asset-binding-missing",
              "No local file is selected for asset " + asset.id()));
      return;
    }
    if (!Files.isRegularFile(local)) {
      diagnostics.add(
          error(
              pointer + "/localBinding",
              "asset-file-unavailable",
              "Selected local asset is not a regular file"));
      return;
    }
    try {
      long size = Files.size(local);
      if (size != asset.sizeBytes()) {
        diagnostics.add(
            error(
                pointer + "/sizeBytes",
                "asset-size-mismatch",
                "Selected local asset size does not match the portable requirement"));
        return;
      }
      String detected = ExperimentAssetMediaType.detect(local);
      if (!asset.mediaType().equals(detected)
          && !(detected == null
              && asset.mediaType().equals(bindings.confirmedMediaTypes().get(asset.id())))) {
        diagnostics.add(
            error(
                pointer + "/mediaType",
                "asset-media-type-mismatch",
                detected == null
                    ? "Confirm the media type of this local asset explicitly"
                    : "Local asset content does not match its declared media type"));
      }
      String sha256 = DocumentHashes.sha256(local);
      if (!sha256.equals(asset.sha256())) {
        diagnostics.add(
            error(
                pointer + "/sha256",
                "asset-hash-mismatch",
                "Selected local asset SHA-256 does not match the portable requirement"));
      }
    } catch (IOException exception) {
      diagnostics.add(
          error(
              pointer + "/localBinding",
              "asset-query-failed",
              "Could not inspect selected local asset: " + safeMessage(exception)));
    }
  }

  private static void inspectOutputDirectory(
      ExperimentDocument document,
      ExperimentLocalBindings bindings,
      ArrayList<DocumentDiagnostic> diagnostics) {
    if (document.outputs().isEmpty()) {
      return;
    }
    Path selected = bindings.selectedOutputDirectory().orElse(null);
    if (selected == null) {
      diagnostics.add(
          error(
              "/localBindings/outputDirectory",
              "output-binding-missing",
              "Select a local output directory for the requested outputs"));
      return;
    }
    if (Files.exists(selected) && !Files.isDirectory(selected)) {
      diagnostics.add(
          error(
              "/localBindings/outputDirectory",
              "output-not-directory",
              "Selected output path is not a directory"));
      return;
    }
    Path existing = nearestExistingAncestor(selected);
    if (existing == null || !Files.isDirectory(existing) || !Files.isWritable(existing)) {
      diagnostics.add(
          error(
              "/localBindings/outputDirectory",
              "output-not-writable",
              "Selected output directory or its nearest existing ancestor is not writable"));
    }
  }

  private static Path nearestExistingAncestor(Path path) {
    Path current = path;
    while (current != null && !Files.exists(current)) {
      current = current.getParent();
    }
    return current;
  }

  private static String escape(String value) {
    return value.replace("~", "~0").replace("/", "~1");
  }

  private static String safeMessage(IOException exception) {
    return exception.getMessage() == null
        ? exception.getClass().getSimpleName()
        : exception.getMessage();
  }

  private static DocumentDiagnostic error(String pointer, String code, String message) {
    return new DocumentDiagnostic(DocumentDiagnostic.Severity.ERROR, pointer, code, message);
  }

  private static DocumentDiagnostic warning(String pointer, String code, String message) {
    return new DocumentDiagnostic(DocumentDiagnostic.Severity.WARNING, pointer, code, message);
  }
}
