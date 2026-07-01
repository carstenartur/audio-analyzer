package org.hammer.audio.experimental.acoustic.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ServiceLoader;
import javax.swing.JComponent;
import org.hammer.audio.experimental.acoustic.workbench.AcousticLocalizationWorkbenchPanel;
import org.hammer.audio.plugin.AudioAnalyzerPlugin;
import org.hammer.audio.plugin.MenuContribution;
import org.hammer.audio.plugin.ViewContribution;
import org.junit.jupiter.api.Test;

class AcousticLocalizationPluginTest {

  @Test
  void pluginIsDiscoverableViaServiceLoader() {
    boolean found =
        ServiceLoader.load(AudioAnalyzerPlugin.class).stream()
            .map(ServiceLoader.Provider::get)
            .anyMatch(p -> p instanceof AcousticLocalizationPlugin);
    assertTrue(found, "AcousticLocalizationPlugin must be registered via ServiceLoader");
  }

  @Test
  void descriptorMatchesIssueSpecification() {
    AcousticLocalizationPlugin plugin = new AcousticLocalizationPlugin();
    assertEquals("acoustic-localization", plugin.descriptor().id());
    assertEquals("Experimental Acoustic Localization", plugin.descriptor().name());
    assertEquals("0.1.0", plugin.descriptor().version());
    assertTrue(plugin.descriptor().experimental());
    assertNotNull(plugin.descriptor().documentationPath());
  }

  @Test
  void contributionsAreNonEmpty() {
    AcousticLocalizationPlugin plugin = new AcousticLocalizationPlugin();
    assertFalse(plugin.analysisContributions().isEmpty());
    assertFalse(plugin.demoSignalContributions().isEmpty());
    assertFalse(plugin.menuContributions().isEmpty());
    assertFalse(plugin.viewContributions().isEmpty());
  }

  @Test
  void workbenchContributionsAreNonEmpty() {
    AcousticLocalizationPlugin plugin = new AcousticLocalizationPlugin();
    assertFalse(
        plugin.signalSourceContributions().isEmpty(),
        "plugin must expose at least one signal source");
    assertFalse(
        plugin.experimentContributions().isEmpty(), "plugin must expose at least one experiment");
    assertFalse(
        plugin.pipelineContributions().isEmpty(), "plugin must expose at least one pipeline");
    assertFalse(
        plugin.snapshotStreamContributions().isEmpty(),
        "plugin must expose at least one snapshot stream");
    assertFalse(
        plugin.visualizationContributions().isEmpty(),
        "plugin must expose at least one visualization");
    assertFalse(
        plugin.calibrationContributions().isEmpty(), "plugin must expose at least one calibration");
    assertFalse(
        plugin.benchmarkContributions().isEmpty(), "plugin must expose at least one benchmark");
    assertFalse(
        plugin.exportFormatContributions().isEmpty(),
        "plugin must expose at least one export format");
  }

  @Test
  void signalSourceContributionsHaveUniqueIds() {
    AcousticLocalizationPlugin plugin = new AcousticLocalizationPlugin();
    long distinctIds =
        plugin.signalSourceContributions().stream().map(s -> s.id()).distinct().count();
    assertEquals(
        plugin.signalSourceContributions().size(), distinctIds, "signal source IDs must be unique");
  }

  @Test
  void experimentContributionsHaveUniqueIds() {
    AcousticLocalizationPlugin plugin = new AcousticLocalizationPlugin();
    long distinctIds =
        plugin.experimentContributions().stream().map(e -> e.id()).distinct().count();
    assertEquals(
        plugin.experimentContributions().size(), distinctIds, "experiment IDs must be unique");
  }

  @Test
  void pipelineContributionsHaveUniqueIds() {
    AcousticLocalizationPlugin plugin = new AcousticLocalizationPlugin();
    long distinctIds = plugin.pipelineContributions().stream().map(p -> p.id()).distinct().count();
    assertEquals(plugin.pipelineContributions().size(), distinctIds, "pipeline IDs must be unique");
  }

  @Test
  void exportFormatsIncludeExpectedFileExtensions() {
    AcousticLocalizationPlugin plugin = new AcousticLocalizationPlugin();
    boolean hasMd =
        plugin.exportFormatContributions().stream().anyMatch(e -> "md".equals(e.fileExtension()));
    boolean hasCsv =
        plugin.exportFormatContributions().stream().anyMatch(e -> "csv".equals(e.fileExtension()));
    boolean hasJsonl =
        plugin.exportFormatContributions().stream()
            .anyMatch(e -> "jsonl".equals(e.fileExtension()));
    assertTrue(hasMd, "plugin must expose a Markdown export format");
    assertTrue(hasCsv, "plugin must expose a CSV export format");
    assertTrue(hasJsonl, "plugin must expose a JSON-lines export format");
  }

  @Test
  void benchmarkContributionsIncludeLocalizationAndClassification() {
    AcousticLocalizationPlugin plugin = new AcousticLocalizationPlugin();
    boolean hasLocalization =
        plugin.benchmarkContributions().stream().anyMatch(b -> b.id().contains("localization"));
    boolean hasClassification =
        plugin.benchmarkContributions().stream().anyMatch(b -> b.id().contains("classification"));
    assertTrue(hasLocalization, "plugin must expose a localization benchmark");
    assertTrue(hasClassification, "plugin must expose a classification benchmark");
  }

  @Test
  void visualizationContributionsInclude2dSpatialRenderKind() {
    AcousticLocalizationPlugin plugin = new AcousticLocalizationPlugin();
    boolean hasSpatial =
        plugin.visualizationContributions().stream()
            .anyMatch(v -> "2d-spatial".equals(v.renderKind()));
    assertTrue(hasSpatial, "plugin must expose a 2d-spatial visualization for the room map");
  }

  @Test
  void viewContributionProducesUniqueComponents() {
    AcousticLocalizationPlugin plugin = new AcousticLocalizationPlugin();
    for (ViewContribution view : plugin.viewContributions()) {
      JComponent first = view.componentFactory().get();
      JComponent second = view.componentFactory().get();
      assertNotNull(first, view.id() + " first component must not be null");
      assertNotNull(second, view.id() + " second component must not be null");
      assertNotSame(
          first, second, "factory for " + view.id() + " must return fresh component instances");
    }
  }

  @Test
  void workbenchViewContributionIsPresent() {
    AcousticLocalizationPlugin plugin = new AcousticLocalizationPlugin();
    boolean hasWorkbench =
        plugin.viewContributions().stream()
            .anyMatch(v -> "acoustic-localization-workbench".equals(v.id()));
    assertTrue(hasWorkbench, "plugin must expose the acoustic-localization-workbench view");
  }

  @Test
  void workbenchViewFactoryProducesWorkbenchPanel() {
    AcousticLocalizationPlugin plugin = new AcousticLocalizationPlugin();
    ViewContribution workbench =
        plugin.viewContributions().stream()
            .filter(v -> "acoustic-localization-workbench".equals(v.id()))
            .findFirst()
            .orElseThrow();
    JComponent component = workbench.componentFactory().get();
    assertNotNull(component);
    assertInstanceOf(
        AcousticLocalizationWorkbenchPanel.class,
        component,
        "workbench view should produce AcousticLocalizationWorkbenchPanel");
  }

  @Test
  void menuActionsAreRunnableWithoutThrowing() {
    AcousticLocalizationPlugin plugin = new AcousticLocalizationPlugin();
    for (MenuContribution menu : plugin.menuContributions()) {
      menu.action().run();
    }
  }
}
