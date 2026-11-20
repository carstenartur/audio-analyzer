package org.hammer.audio.experiment.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.hammer.audio.plugin.document.DocumentDiagnostic;

/**
 * Separate, streamed evidence packages containing a setup and explicitly selected local evidence.
 */
public final class ExperimentEvidenceExporter {
  private static final long MAX_PACKAGE_BYTES = 2L * 1024 * 1024 * 1024;
  private static final String DOCUMENT_ENTRY = "experiment.audioexp";
  private static final String MANIFEST_ENTRY = "evidence-manifest.json";

  /**
   * Validate selected evidence before opening any output. Compatibility/read-only status remains
   * inspectable.
   */
  public void validate(ExperimentDocumentPreview preview, ExperimentLocalBindings bindings)
      throws IOException {
    if (preview.document().assets().size() > 64) {
      throw new IOException("Evidence packages support at most 64 assets");
    }
    long declared = 0;
    java.util.Set<String> entryNames = new java.util.HashSet<>();
    for (ExperimentDocument.AssetReference asset : preview.document().assets()) {
      if (!entryNames.add(asset.relativePath())
          || DOCUMENT_ENTRY.equals(asset.relativePath())
          || MANIFEST_ENTRY.equals(asset.relativePath())
          || asset.relativePath().startsWith("results/")) {
        throw new ExperimentDocumentException(
            "/assets",
            "evidence-path-conflict",
            "Asset path conflicts with a package-reserved entry");
      }
      if (asset.sizeBytes() > MAX_PACKAGE_BYTES - declared) {
        throw new IOException("Evidence package exceeds the 2 GiB limit");
      }
      declared += asset.sizeBytes();
    }
    for (ExperimentDocument.OutputRequest requested : preview.document().outputs()) {
      if (!entryNames.add("results/" + requested.baseName())) {
        throw new ExperimentDocumentException(
            "/outputs",
            "evidence-path-conflict",
            "Requested outputs must have distinct package entry names");
      }
    }
    List<DocumentDiagnostic> diagnostics =
        new ExperimentLocalBindingService().inspect(preview, bindings).diagnostics();
    for (DocumentDiagnostic diagnostic : diagnostics) {
      if (diagnostic.severity() == DocumentDiagnostic.Severity.ERROR
          && diagnostic.pointer().startsWith("/assets/")) {
        throw new ExperimentDocumentException(
            diagnostic.pointer(), diagnostic.code(), diagnostic.message());
      }
    }
  }

  /**
   * Atomically save a package to an explicitly selected destination, preserving original and
   * evidence files.
   */
  public void save(
      Path target,
      Path importedOriginal,
      ExperimentDocumentPreview preview,
      ExperimentLocalBindings bindings)
      throws IOException {
    validate(preview, bindings);
    Path destination = target.toAbsolutePath().normalize();
    List<Path> protectedPaths = new ArrayList<>(bindings.assetPaths().values());
    if (importedOriginal != null) {
      protectedPaths.add(importedOriginal);
    }
    if (bindings.outputDirectory() != null) {
      for (ExperimentDocument.OutputRequest requested : preview.document().outputs()) {
        Path source = bindings.outputDirectory().resolve(requested.baseName());
        if (Files.exists(source)) {
          protectedPaths.add(source);
        }
      }
    }
    for (Path source : protectedPaths) {
      if (destination.equals(source.toAbsolutePath().normalize())
          || Files.exists(destination) && Files.isSameFile(destination, source)) {
        throw new IOException(
            "Evidence destination must differ from imported source and selected assets");
      }
    }
    Path parent = destination.getParent();
    if (parent == null) {
      throw new IOException("Evidence destination requires a parent directory");
    }
    Files.createDirectories(parent);
    Path temporary = Files.createTempFile(parent, ".audio-evidence-", ".partial");
    try {
      try (OutputStream output = Files.newOutputStream(temporary)) {
        write(output, preview, bindings);
      }
      if (Thread.currentThread().isInterrupted()) {
        throw new java.io.InterruptedIOException(
            "Evidence export cancelled before atomic replacement");
      }
      Files.move(
          temporary,
          destination,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  /** Stream an evidence ZIP and close its output; no local files are created by this method. */
  // The sorted manifest is local to one export and must preserve reproducible key ordering.
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  public void write(
      OutputStream output, ExperimentDocumentPreview preview, ExperimentLocalBindings bindings)
      throws IOException {
    validate(preview, bindings);
    List<Map<String, Object>> entries = new ArrayList<>();
    long[] total = {0};
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      byte[] document = new ExperimentDocumentCodec().encode(preview.document());
      put(zip, DOCUMENT_ENTRY);
      zip.write(document);
      zip.closeEntry();
      total[0] += document.length;
      List<ExperimentDocument.AssetReference> sortedAssets =
          preview.document().assets().stream()
              .sorted(Comparator.comparing(ExperimentDocument.AssetReference::relativePath))
              .toList();
      for (ExperimentDocument.AssetReference asset : sortedAssets) {
        Path source = bindings.assetPath(asset.id()).orElseThrow();
        String hash = copy(zip, asset.relativePath(), source, asset.sizeBytes(), total);
        if (!hash.equals(asset.sha256())) {
          throw new IOException("Selected evidence changed while being packaged: " + asset.id());
        }
        entries.add(
            Map.of(
                "path",
                asset.relativePath(),
                "sha256",
                hash,
                "sizeBytes",
                asset.sizeBytes(),
                "mediaType",
                asset.mediaType()));
      }
      List<String> unavailable = new ArrayList<>();
      for (ExperimentDocument.OutputRequest requested : preview.document().outputs()) {
        Path directory = bindings.outputDirectory();
        Path result = directory == null ? null : directory.resolve(requested.baseName());
        if (result == null || !Files.isRegularFile(result, LinkOption.NOFOLLOW_LINKS)) {
          unavailable.add(requested.id());
          continue;
        }
        long size = Files.size(result);
        String name = "results/" + requested.baseName();
        String hash = copy(zip, name, result, size, total);
        entries.add(
            Map.of(
                "path",
                name,
                "sha256",
                hash,
                "sizeBytes",
                size,
                "mediaType",
                requested.mediaType()));
      }
      Map<String, Object> manifest = new TreeMap<>();
      manifest.put("format", "io.github.carstenartur.audio-analyzer.evidence");
      manifest.put("formatVersion", 1);
      manifest.put("documentCanonicalSha256", preview.canonicalSha256());
      manifest.put("entries", entries);
      manifest.put("unavailableOutputs", unavailable);
      put(zip, MANIFEST_ENTRY);
      zip.write(new ObjectMapper().writeValueAsBytes(manifest));
      zip.closeEntry();
    }
  }

  private static String copy(
      ZipOutputStream zip, String name, Path source, long expected, long[] total)
      throws IOException {
    if (expected > MAX_PACKAGE_BYTES - total[0]) {
      throw new IOException("Evidence package exceeds the 2 GiB limit");
    }
    put(zip, name);
    java.security.MessageDigest digest;
    try {
      digest = java.security.MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
    long count = 0;
    byte[] buffer = new byte[8192];
    try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
      int read = input.read(buffer);
      while (read >= 0) {
        if (Thread.currentThread().isInterrupted()) {
          throw new java.io.InterruptedIOException("Evidence export cancelled");
        }
        count += read;
        if (count > expected) {
          throw new IOException("Selected evidence grew during export");
        }
        digest.update(buffer, 0, read);
        zip.write(buffer, 0, read);
        read = input.read(buffer);
      }
    }
    if (count != expected) {
      throw new IOException("Selected evidence changed size during export");
    }
    total[0] += count;
    zip.closeEntry();
    return java.util.HexFormat.of().formatHex(digest.digest());
  }

  private static void put(ZipOutputStream zip, String name) throws IOException {
    PortableNames.requireRelativePath(name);
    ZipEntry entry = new ZipEntry(name);
    entry.setTime(0);
    zip.putNextEntry(entry);
  }
}
