package org.hammer.audio.experiment.document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hammer.audio.plugin.AudioAnalyzerPlugin;
import org.hammer.audio.plugin.PluginDescriptor;
import org.hammer.audio.plugin.document.DocumentValidationResult;
import org.hammer.audio.plugin.document.DocumentValue;
import org.hammer.audio.plugin.document.ExperimentDocumentContribution;
import org.hammer.audio.plugin.document.ExperimentSectionMigration;
import org.junit.jupiter.api.Test;

class ExperimentDocumentCodecTest {

  private static final String WORKFLOW =
      "workflow\n  id: experiment-workflow\n  name: Portable workflow\n  nodes:\n  edges:\n";

  @Test
  void canonicalSaveLoadSaveIsByteStable() throws Exception {
    ExperimentDocumentCodec codec = new ExperimentDocumentCodec();
    ExperimentDocument source = document(Map.of(), List.of());

    byte[] first = codec.encode(source);
    ExperimentDocument loaded = codec.decode(first);
    byte[] second = codec.encode(loaded);

    assertArrayEquals(first, second);
    assertEquals(DocumentHashes.sha256(WORKFLOW), loaded.workflow().sha256());
    assertEquals(64, loaded.provenance().canonicalSha256().length());
  }

  @Test
  void duplicateCoreKeysAreRejected() throws Exception {
    ExperimentDocumentCodec codec = new ExperimentDocumentCodec();
    String json = new String(codec.encode(document(Map.of(), List.of())), StandardCharsets.UTF_8);
    String malicious = json.replaceFirst("\\\"format\\\":", "\\\"format\\\":\\\"shadow\\\",\\\"format\\\":");

    ExperimentDocumentException exception =
        assertThrows(
            ExperimentDocumentException.class,
            () -> codec.decode(malicious.getBytes(StandardCharsets.UTF_8)));

    assertEquals("invalid-json", exception.code());
  }

  @Test
  void traversalAndAbsoluteOutputNamesAreRejectedBeforeSave() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExperimentDocument.AssetReference("asset", "../secret.wav", "audio/wav", 1L, zeros()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExperimentDocument.OutputRequest("report", "text/markdown", "../report.md"));
  }

  @Test
  void missingRequiredPluginRemainsInspectableButBlocksExecution() throws Exception {
    ExperimentDocument.PluginSection section =
        new ExperimentDocument.PluginSection(
            1, "algorithm/1", DocumentValue.object(Map.of("value", DocumentValue.number(java.math.BigDecimal.ONE))));
    ExperimentDocument source =
        document(
            Map.of("missing-plugin", Map.of("settings", section)),
            List.of(new ExperimentDocument.PluginRequirement("missing-plugin", "*", List.of("settings"))));
    ExperimentDocumentCodec codec = new ExperimentDocumentCodec();
    ExperimentDocument loaded = codec.decode(codec.encode(source));

    ExperimentDocumentPreview preview = PluginDocumentCatalog.empty().preview(loaded, codec);

    assertFalse(preview.executionAllowed());
    assertTrue(preview.readOnly());
    assertEquals(section, preview.document().pluginData().get("missing-plugin").get("settings"));
    assertTrue(preview.diagnostics().stream().anyMatch(item -> item.code().equals("missing-plugin")));
  }

  @Test
  void optionalUnknownPluginDataSurvivesCanonicalRoundTrip() throws Exception {
    ExperimentDocument.PluginSection section =
        new ExperimentDocument.PluginSection(
            7, "future/7", DocumentValue.object(Map.of("opaque", DocumentValue.string("preserve"))));
    ExperimentDocumentCodec codec = new ExperimentDocumentCodec();
    ExperimentDocument loaded = codec.decode(codec.encode(document(Map.of("future-plugin", Map.of("opaque-section", section)), List.of())));

    ExperimentDocumentPreview preview = PluginDocumentCatalog.empty().preview(loaded, codec);
    ExperimentDocument roundTrip = codec.decode(codec.encode(preview.document()));

    assertTrue(preview.executionAllowed());
    assertTrue(preview.readOnly());
    assertEquals(section, roundTrip.pluginData().get("future-plugin").get("opaque-section"));
  }

  @Test
  void knownPluginSectionMigratesOneVersionAndValidatesLocally() throws Exception {
    String schema =
        "{\"type\":\"object\",\"required\":[\"gain\"],\"properties\":{\"gain\":{\"type\":\"number\",\"minimum\":0}},\"additionalProperties\":false}";
    ExperimentDocumentContribution contribution = contribution(schema);
    AudioAnalyzerPlugin plugin =
        new AudioAnalyzerPlugin() {
          @Override
          public PluginDescriptor descriptor() {
            return new PluginDescriptor("gain-plugin", "Gain", "1.0.0", "Gain settings", null, false);
          }

          @Override
          public List<ExperimentDocumentContribution> experimentDocumentContributions() {
            return List.of(contribution);
          }
        };
    ExperimentDocument.PluginSection section =
        new ExperimentDocument.PluginSection(
            1,
            "gain/2",
            DocumentValue.object(Map.of("gain", DocumentValue.number(java.math.BigDecimal.ONE))));
    ExperimentDocumentCodec codec = new ExperimentDocumentCodec();
    ExperimentDocument loaded =
        codec.decode(codec.encode(document(Map.of("gain-plugin", Map.of("gain-settings", section)), List.of())));

    ExperimentDocumentPreview preview = new PluginDocumentCatalog(List.of(plugin)).preview(loaded, codec);

    assertTrue(preview.executionAllowed());
    assertEquals(List.of("gain-settings:1->2"), preview.migrations());
    assertEquals(
        2, preview.document().pluginData().get("gain-plugin").get("gain-settings").schemaVersion());
  }

  private static ExperimentDocumentContribution contribution(String schema) {
    return new ExperimentDocumentContribution() {
      @Override
      public String sectionId() {
        return "gain-settings";
      }

      @Override
      public int schemaVersion() {
        return 2;
      }

      @Override
      public String algorithmVersion() {
        return "gain/2";
      }

      @Override
      public String name() {
        return "Gain settings";
      }

      @Override
      public String description() {
        return "Portable gain parameters";
      }

      @Override
      public boolean requiredForExecution() {
        return false;
      }

      @Override
      public String schemaId() {
        return "urn:audio-analyzer:test:gain-settings:2";
      }

      @Override
      public String schemaJson() {
        return schema;
      }

      @Override
      public String schemaSha256() {
        return DocumentHashes.sha256(schema.getBytes(StandardCharsets.UTF_8));
      }

      @Override
      public List<ExperimentSectionMigration> migrations() {
        return List.of(
            new ExperimentSectionMigration() {
              @Override
              public int fromVersion() {
                return 1;
              }

              @Override
              public int toVersion() {
                return 2;
              }

              @Override
              public DocumentValue migrate(DocumentValue source) {
                return source;
              }
            });
      }

      @Override
      public DocumentValidationResult validateAndNormalize(DocumentValue value) {
        return DocumentValidationResult.valid(value);
      }
    };
  }

  private static ExperimentDocument document(
      Map<String, Map<String, ExperimentDocument.PluginSection>> pluginData,
      List<ExperimentDocument.PluginRequirement> requirements) {
    return new ExperimentDocument(
        ExperimentDocumentFormat.SCHEMA_RESOURCE,
        ExperimentDocumentFormat.FORMAT_ID,
        ExperimentDocumentFormat.VERSION,
        new ExperimentDocument.ExperimentInfo(
            "portable-experiment",
            "Portable experiment",
            "Round-trip fixture",
            List.of("fixture"),
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
        requirements,
        pluginData,
        List.of(),
        List.of(new ExperimentDocument.OutputRequest("report", "text/markdown", "report.md")),
        new ExperimentDocument.Provenance(
            "Test Author",
            "",
            Instant.parse("2026-07-23T12:00:00Z"),
            Instant.parse("2026-07-23T12:00:00Z"),
            "0.0.4-SNAPSHOT",
            "",
            List.of()));
  }

  private static String zeros() {
    return "0".repeat(64);
  }
}
