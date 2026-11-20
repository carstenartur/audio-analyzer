package org.hammer.audio.experiment.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.hammer.audio.plugin.AudioAnalyzerPlugin;
import org.hammer.audio.plugin.PluginDescriptor;
import org.hammer.audio.plugin.document.DocumentDiagnostic;
import org.hammer.audio.plugin.document.DocumentValidationResult;
import org.hammer.audio.plugin.document.DocumentValue;
import org.hammer.audio.plugin.document.ExperimentDocumentContribution;
import org.junit.jupiter.api.Test;

class PluginDocumentSafetyTest {

  private static final String INTEGER_SCHEMA =
      "{\"type\":\"object\",\"required\":[\"count\"],\"properties\":{\"count\":{\"type\":\"integer\"}},\"additionalProperties\":false}";
  private final ExperimentDocumentCodec codec = new ExperimentDocumentCodec();

  @Test
  void nullValidatorResultPreservesOriginalAndReportsTheSection() throws Exception {
    ExperimentDocument source = source(value("2"), "*");
    ExperimentDocumentPreview preview = service(ignored -> null).preview(codec.encode(source));
    assertBlockedAndPreserved(source, preview, "plugin-validator-failed");
  }

  @Test
  void invalidNormalizedValueCannotBecomeExecutableOrReplaceOriginal() throws Exception {
    ExperimentDocument source = source(value("2"), "*");
    ExperimentDocumentPreview preview =
        service(ignored -> DocumentValidationResult.valid(value("2.5")))
            .preview(codec.encode(source));
    assertBlockedAndPreserved(source, preview, "schema-type");
  }

  @Test
  void schemaFailureDoesNotInvokeSemanticCode() throws Exception {
    AtomicBoolean invoked = new AtomicBoolean();
    ExperimentDocumentPreview preview =
        service(
                value -> {
                  invoked.set(true);
                  return DocumentValidationResult.valid(value);
                })
            .preview(codec.encode(source(value("2.5"), "*")));
    assertFalse(invoked.get());
    assertFalse(preview.executionAllowed());
  }

  @Test
  void semanticDiagnosticsAreScopedToTheNamespacedData() throws Exception {
    ExperimentDocumentPreview preview =
        service(
                value ->
                    new DocumentValidationResult(
                        value,
                        List.of(
                            new DocumentDiagnostic(
                                DocumentDiagnostic.Severity.ERROR,
                                "/count",
                                "invalid-count",
                                "Count is incompatible"))))
            .preview(codec.encode(source(value("2"), "*")));
    assertEquals(
        "/pluginData/example/settings/data/count", preview.diagnostics().getFirst().pointer());
  }

  @Test
  void supportedPackageIntervalDoesNotReplaceAlgorithmCompatibility() throws Exception {
    ExperimentDocumentPreview preview =
        service(DocumentValidationResult::valid)
            .preview(codec.encode(source(value("2"), "[0.1,1.0)")));
    assertTrue(preview.executionAllowed());
    assertFalse(preview.readOnly());
  }

  @Test
  void stalledValidatorIsBoundedAndPreservesOriginal() throws Exception {
    ExperimentDocument source = source(value("2"), "*");
    ExperimentDocumentService service =
        service(
            value -> {
              try {
                Thread.sleep(10_000);
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              }
              return DocumentValidationResult.valid(value);
            });
    ExperimentDocumentPreview preview =
        assertTimeoutPreemptively(
            Duration.ofSeconds(3), () -> service.preview(codec.encode(source)));
    assertBlockedAndPreserved(source, preview, "plugin-timeout");
  }

  @Test
  void oversizedNormalizedOutputIsASectionDiagnostic() throws Exception {
    ExperimentDocument source = source(value("2"), "*");
    ExperimentDocumentPreview preview =
        service(
                ignored ->
                    DocumentValidationResult.valid(
                        DocumentValue.object(
                            Map.of(
                                "count",
                                DocumentValue.string(
                                    "x".repeat(ExperimentDocumentFormat.MAX_STRING_LENGTH + 1))))))
            .preview(codec.encode(source));
    assertFalse(preview.executionAllowed());
    assertEquals(source.pluginData(), preview.document().pluginData());
    assertTrue(
        preview.diagnostics().stream()
            .allMatch(item -> item.pointer().startsWith("/pluginData/example/settings")));
  }

  private void assertBlockedAndPreserved(
      ExperimentDocument source, ExperimentDocumentPreview preview, String code) {
    assertFalse(preview.executionAllowed());
    assertTrue(preview.readOnly());
    assertEquals(source.pluginData(), preview.document().pluginData());
    assertTrue(preview.migrations().isEmpty());
    assertTrue(
        preview.diagnostics().stream().anyMatch(item -> item.code().equals(code)),
        preview.diagnostics().toString());
  }

  private static DocumentValue value(String count) {
    return DocumentValue.object(Map.of("count", DocumentValue.number(new BigDecimal(count))));
  }

  private ExperimentDocument source(DocumentValue data, String range) throws Exception {
    ExperimentDocument base =
        codec.load(ExperimentDocumentSecurityTest.fixture("minimal.audioexp"));
    return new ExperimentDocument(
        base.schema(),
        base.format(),
        base.formatVersion(),
        base.experiment(),
        base.workflow(),
        base.profiles(),
        List.of(new ExperimentDocument.PluginRequirement("example", range, List.of("settings"))),
        Map.of(
            "example",
            Map.of("settings", new ExperimentDocument.PluginSection(1, "count/1", data))),
        base.assets(),
        base.outputs(),
        base.provenance());
  }

  private static ExperimentDocumentService service(
      Function<DocumentValue, DocumentValidationResult> handler) {
    ExperimentDocumentContribution contribution = new TestContribution(handler);
    AudioAnalyzerPlugin plugin =
        new AudioAnalyzerPlugin() {
          @Override
          public PluginDescriptor descriptor() {
            return new PluginDescriptor(
                "example", "Example", "0.2.0", "Example section", null, false);
          }

          @Override
          public List<ExperimentDocumentContribution> experimentDocumentContributions() {
            return List.of(contribution);
          }
        };
    return new ExperimentDocumentService(List.of(plugin));
  }

  private record TestContribution(Function<DocumentValue, DocumentValidationResult> handler)
      implements ExperimentDocumentContribution {
    @Override
    public String sectionId() {
      return "settings";
    }

    @Override
    public int schemaVersion() {
      return 1;
    }

    @Override
    public String algorithmVersion() {
      return "count/1";
    }

    @Override
    public String name() {
      return "Settings";
    }

    @Override
    public String description() {
      return "Example settings";
    }

    @Override
    public boolean requiredForExecution() {
      return true;
    }

    @Override
    public String schemaId() {
      return "urn:example:settings:1";
    }

    @Override
    public String schemaJson() {
      return INTEGER_SCHEMA;
    }

    @Override
    public String schemaSha256() {
      return PluginDocumentTestKit.schemaSha256(INTEGER_SCHEMA);
    }

    @Override
    public DocumentValidationResult validateAndNormalize(DocumentValue value) {
      return handler.apply(value);
    }
  }
}
