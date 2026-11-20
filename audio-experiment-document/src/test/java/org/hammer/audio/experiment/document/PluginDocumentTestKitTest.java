package org.hammer.audio.experiment.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.hammer.audio.experiment.document.examples.GainDocumentContribution;
import org.hammer.audio.plugin.AudioAnalyzerPlugin;
import org.hammer.audio.plugin.PluginDescriptor;
import org.hammer.audio.plugin.document.DocumentValue;
import org.hammer.audio.plugin.document.ExperimentDocumentContribution;
import org.junit.jupiter.api.Test;

class PluginDocumentTestKitTest {
  @Test
  void bundledExampleSchemaDigestAndMigrationAreCanonical() throws Exception {
    var example = new GainDocumentContribution();
    assertEquals(example.schemaSha256(), PluginDocumentTestKit.schemaSha256(example.schemaJson()));
    assertEquals(
        example.schemaSha256(),
        PluginDocumentTestKit.schemaSha256(" \n" + example.schemaJson() + "\n"));
    var migrated =
        example
            .migrations()
            .getFirst()
            .migrate(
                DocumentValue.object(Map.of("gainLinear", DocumentValue.number(BigDecimal.ONE))));
    assertEquals(
        DocumentValue.object(Map.of("gain", DocumentValue.number(BigDecimal.ONE))), migrated);
    assertEquals(
        "/gain", PluginDocumentTestKit.parameters(example.schemaJson()).getFirst().pointer());
    var service = new ExperimentDocumentService(List.of(plugin("one", example)));
    assertEquals(1, service.pluginSections().size());
  }

  @Test
  void duplicateSectionIdsAreRejectedAndIndependentPluginNamespacesAreSupported() {
    var example = new GainDocumentContribution();
    var service =
        new ExperimentDocumentService(List.of(plugin("one", example), plugin("two", example)));
    assertEquals(2, service.pluginSections().size());
    assertThrows(
        RuntimeException.class,
        () -> new ExperimentDocumentService(List.of(plugin("same", example, example))));
  }

  @Test
  void fixtureRunnerChecksDeterminismAndIdempotence() throws Exception {
    var service =
        new ExperimentDocumentService(List.of(ExperimentDocumentFixtureTest.gainPlugin()));
    byte[] old =
        java.nio.file.Files.readAllBytes(
            ExperimentDocumentSecurityTest.fixture("legacy-plugin.audioexp"));
    byte[] expected =
        java.nio.file.Files.readAllBytes(
            ExperimentDocumentSecurityTest.fixture("migrated-plugin.audioexp"));
    PluginDocumentTestKit.verifyFixture(service, old, expected);
    assertThrows(
        IllegalArgumentException.class,
        () -> PluginDocumentTestKit.verifyFixture(service, old, old));
  }

  @Test
  void versionRangesDoNotGuessUnsupportedVersionSemantics() {
    assertTrue(PluginVersionRange.matches("[0.1,1.0)", "0.2.0"));
    assertFalse(PluginVersionRange.matches("[0.1,1.0)", "1.0.0"));
    assertFalse(PluginVersionRange.matches("[0.1,1.0)", "0.2.0-beta"));
    assertFalse(PluginVersionRange.matches("latest", "1.0"));
    assertTrue(PluginVersionRange.matches("1.0-beta", "1.0-beta"));
  }

  private static AudioAnalyzerPlugin plugin(
      String id, ExperimentDocumentContribution... contributions) {
    return new AudioAnalyzerPlugin() {
      @Override
      public PluginDescriptor descriptor() {
        return new PluginDescriptor(id, id, "0.2.0", "Fixture", null, false);
      }

      @Override
      public List<ExperimentDocumentContribution> experimentDocumentContributions() {
        return List.of(contributions);
      }
    };
  }
}
