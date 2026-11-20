package org.hammer.audio.experiment.document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExperimentEvidenceExporterTest {
  @TempDir Path directory;

  @Test
  void explicitEvidenceExportIncludesCanonicalSetupAndVerifiedAssets() throws Exception {
    Path source = ExperimentDocumentSecurityTest.fixture("full.audioexp");
    var service = new ExperimentDocumentService(List.of());
    var preview = service.preview(source);
    Map<String, Path> assets = new java.util.TreeMap<>();
    preview
        .document()
        .assets()
        .forEach(asset -> assets.put(asset.id(), source.getParent().resolve(asset.relativePath())));
    Map<String, String> types = new java.util.TreeMap<>();
    preview.document().assets().forEach(asset -> types.put(asset.id(), asset.mediaType()));
    Path target = directory.resolve("evidence.zip");
    new ExperimentEvidenceExporter()
        .save(target, source, preview, new ExperimentLocalBindings(assets, null, null, types));
    try (ZipFile zip = new ZipFile(target.toFile())) {
      assertArrayEquals(
          new ExperimentDocumentCodec().encode(preview.document()),
          zip.getInputStream(zip.getEntry("experiment.audioexp")).readAllBytes());
      for (var asset : preview.document().assets()) {
        assertEquals(asset.sizeBytes(), zip.getEntry(asset.relativePath()).getSize());
      }
      assertTrue(zip.getEntry("evidence-manifest.json") != null);
    }
  }

  @Test
  void failedAssetValidationNeverReplacesDestination() throws Exception {
    Path source = ExperimentDocumentSecurityTest.fixture("full.audioexp");
    var preview = new ExperimentDocumentService(List.of()).preview(source);
    Path target = directory.resolve("evidence.zip");
    Files.writeString(target, "original");
    assertThrows(
        java.io.IOException.class,
        () ->
            new ExperimentEvidenceExporter()
                .save(target, source, preview, new ExperimentLocalBindings(Map.of(), null)));
    assertEquals("original", Files.readString(target));
    try (var files = Files.list(directory)) {
      assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".partial")));
    }
  }
}
