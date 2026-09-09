package org.hammer.audio.experiment.document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.hammer.audio.plugin.AudioAnalyzerPlugin;
import org.hammer.audio.plugin.PluginDescriptor;
import org.hammer.audio.plugin.document.DocumentDiagnostic;
import org.hammer.audio.plugin.document.DocumentValue;
import org.hammer.audio.plugin.document.ExperimentDocumentContribution;
import org.junit.jupiter.api.Test;

class ExperimentDocumentFixtureTest {

  private final ExperimentDocumentCodec codec = new ExperimentDocumentCodec();

  @Test
  void documentedMinimalFixtureIsCanonicalAndByteStable() throws Exception {
    Path fixture = fixture("minimal.audioexp");
    byte[] original = Files.readAllBytes(fixture);

    ExperimentDocument document = codec.decode(original);

    assertEquals("example.experiment", document.experiment().id());
    assertArrayEquals(original, codec.encode(document));
  }

  @Test
  void documentedUnknownOptionalPluginFixtureIsPreservedReadOnly() throws Exception {
    Path fixture = fixture("unknown-optional-plugin.audioexp");
    byte[] original = Files.readAllBytes(fixture);
    ExperimentDocument document = codec.decode(original);

    ExperimentDocumentPreview preview = PluginDocumentCatalog.empty().preview(document, codec);

    assertTrue(preview.readOnly());
    assertTrue(preview.executionAllowed());
    assertTrue(
        preview.diagnostics().stream()
            .anyMatch(
                item ->
                    item.severity() == DocumentDiagnostic.Severity.WARNING
                        && item.code().equals("missing-plugin")));
    assertArrayEquals(original, codec.encode(preview.document()));
  }

  @Test
  void fullFixtureIsSchemaValidAndPreservesEveryPortableSection() throws Exception {
    Path source = fixture("full.audioexp");
    byte[] original = Files.readAllBytes(source);
    ExperimentDocumentPreview preview = new ExperimentDocumentService(List.of()).preview(source);
    ExperimentDocument document = preview.document();
    assertTrue(preview.executionAllowed());
    assertFalse(preview.readOnly());
    assertEquals(ExperimentDocumentFormat.SCHEMA_URI, document.schema());
    assertEquals("recording", document.experiment().sourceMode());
    assertEquals(5, document.profiles().fields().size());
    assertEquals(Map.of("waveform", "1"), document.provenance().algorithmVersions());
    assertEquals("example-run", document.provenance().sourceRun());
    assertArrayEquals(original, codec.encode(document));
    for (ExperimentDocument.AssetReference asset : document.assets()) {
      byte[] content = Files.readAllBytes(source.getParent().resolve(asset.relativePath()));
      assertEquals(asset.sizeBytes(), content.length);
      assertEquals(asset.sha256(), DocumentHashes.sha256(content));
    }
  }

  @Test
  void everyDocumentedInvalidFixtureIsRejectedForItsActualDefect() throws Exception {
    Map<String, String> cases =
        Map.of(
            "future-version",
            "unsupported-version",
            "traversal",
            "schema-invalid",
            "duplicate-key",
            "invalid-json",
            "active-content",
            "schema-invalid");
    for (var entry : cases.entrySet()) {
      ExperimentDocumentException exception =
          assertThrows(
              ExperimentDocumentException.class,
              () -> codec.load(fixture("invalid/" + entry.getKey() + ".audioexp")));
      assertEquals(entry.getValue(), exception.code());
    }
  }

  @Test
  void profileCrossReferencesChannelMappingAndOutputCollisionsAreValidated() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode root = (ObjectNode) mapper.readTree(Files.readAllBytes(fixture("full.audioexp")));
    List<String> targets =
        List.of(
            "/profiles/source/assetId",
            "/profiles/capture/channels",
            "/profiles/microphoneArray/microphones/1/channel",
            "/profiles/calibration/evidenceAssetId",
            "/profiles/calibration/referenceChannel",
            "/profiles/calibration/validUntil");
    for (String pointer : targets) {
      ObjectNode changed = root.deepCopy();
      String field = pointer.substring(pointer.lastIndexOf('/') + 1);
      ObjectNode parent = (ObjectNode) changed.at(pointer.substring(0, pointer.lastIndexOf('/')));
      switch (field) {
        case "channels" -> parent.put(field, 3);
        case "channel" -> parent.put(field, 0);
        case "referenceChannel" -> parent.put(field, 7);
        case "validUntil" -> parent.put(field, "2025-01-01T00:00:00Z");
        default -> parent.put(field, "missing-asset");
      }
      ExperimentDocumentException exception =
          assertThrows(
              ExperimentDocumentException.class,
              () -> codec.decode(mapper.writeValueAsBytes(changed)));
      assertEquals("invalid-setup", exception.code(), pointer);
    }
    ObjectNode secondOutput = ((ObjectNode) root.path("outputs").get(0)).deepCopy();
    secondOutput.put("id", "second-report").put("baseName", "REPORT.md");
    root.withArray("outputs").add(secondOutput);
    assertEquals(
        "invalid-setup",
        assertThrows(
                ExperimentDocumentException.class,
                () -> codec.decode(mapper.writeValueAsBytes(root)))
            .code());
  }

  @Test
  void checkedInPluginMigrationIsExplicitDeterministicAndPreservesTheOriginal() throws Exception {
    byte[] original = Files.readAllBytes(fixture("legacy-plugin.audioexp"));
    ExperimentDocumentService service = new ExperimentDocumentService(List.of(gainPlugin()));
    ExperimentDocumentPreview preview = service.preview(original);
    assertTrue(preview.executionAllowed());
    assertEquals(List.of("gain-settings:1->2"), preview.migrations());
    byte[] expected = Files.readAllBytes(fixture("migrated-plugin.audioexp"));
    assertArrayEquals(expected, service.normalize(original));
    assertArrayEquals(expected, service.normalize(expected));
    assertArrayEquals(original, Files.readAllBytes(fixture("legacy-plugin.audioexp")));
  }

  @Test
  void incompatibleAlgorithmsAndPinnedPackagesRemainBlockedAfterSaving() throws Exception {
    ExperimentDocument source = codec.load(fixture("legacy-plugin.audioexp"));
    ExperimentDocument.PluginSection section =
        new ExperimentDocument.PluginSection(
            1,
            "other/1",
            DocumentValue.object(Map.of("gain", DocumentValue.number(BigDecimal.ONE))));
    ExperimentDocument incompatible =
        new ExperimentDocument(
            source.schema(),
            source.format(),
            1,
            source.experiment(),
            source.workflow(),
            source.profiles(),
            List.of(
                new ExperimentDocument.PluginRequirement(
                    "gain-plugin", "2.0.0", List.of("gain-settings"))),
            Map.of("gain-plugin", Map.of("gain-settings", section)),
            source.assets(),
            source.outputs(),
            source.provenance());
    ExperimentDocumentService service = new ExperimentDocumentService(List.of(gainPlugin()));
    byte[] bytes = codec.encode(incompatible);
    ExperimentDocumentPreview preview = service.preview(bytes);
    assertFalse(preview.executionAllowed());
    assertTrue(preview.readOnly());
    assertTrue(preview.migrations().isEmpty());
    assertArrayEquals(bytes, service.normalize(bytes));
    assertFalse(service.preview(service.normalize(bytes)).executionAllowed());
    assertTrue(
        preview.diagnostics().stream()
            .anyMatch(item -> "package-incompatible".equals(item.code())));
    assertTrue(
        preview.diagnostics().stream()
            .anyMatch(item -> "algorithm-incompatible".equals(item.code())));
  }

  private static AudioAnalyzerPlugin gainPlugin() {
    return new AudioAnalyzerPlugin() {
      @Override
      public PluginDescriptor descriptor() {
        return new PluginDescriptor("gain-plugin", "Gain", "1.0.0", "Fixture plugin", null, false);
      }

      @Override
      public List<ExperimentDocumentContribution> experimentDocumentContributions() {
        return List.of(
            ExperimentDocumentCodecTest.contribution(
                "{\"type\":\"object\",\"required\":[\"gain\"],\"properties\":{\"gain\":{\"type\":\"number\",\"minimum\":0}},\"additionalProperties\":false}"));
      }
    };
  }

  private static Path fixture(String filename) {
    String root = System.getProperty("maven.multiModuleProjectDirectory", "..");
    return Path.of(root).resolve("docs").resolve("examples").resolve(filename).normalize();
  }
}
