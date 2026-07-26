package org.hammer.audio.plugin;

import java.util.List;
import org.hammer.audio.plugin.document.ExperimentDocumentContribution;

/**
 * Central plugin contract for the Audio Analyzer host application.
 *
 * <p>Plugins implement this interface and register their implementation via {@link
 * java.util.ServiceLoader} (i.e. by adding a {@code
 * META-INF/services/org.hammer.audio.plugin.AudioAnalyzerPlugin} resource to their JAR). The host
 * application discovers all such implementations at runtime through a plugin manager and offers
 * their contributions to the user.
 *
 * <p>Each contribution accessor returns an immutable list. Plugins that do not supply a particular
 * contribution type return an empty list. Plugins must not import any internal host application
 * classes; all interaction with the host goes through this API package.
 */
public interface AudioAnalyzerPlugin {

  /** Returns the plugin descriptor (metadata) for this plugin. Must not be {@code null}. */
  PluginDescriptor descriptor();

  /**
   * Returns analysis contributions provided by this plugin (e.g. additional analyzers, derived
   * snapshots, post-processing). Default: empty.
   */
  default List<AnalysisContribution> analysisContributions() {
    return List.of();
  }

  /**
   * Returns view contributions (panels, frames) provided by this plugin. The host decides where to
   * surface them (menu, sidebar, plugin tab). Default: empty.
   */
  default List<ViewContribution> viewContributions() {
    return List.of();
  }

  /**
   * Returns menu contributions provided by this plugin (named actions to be exposed in a Plugins
   * menu). Default: empty.
   */
  default List<MenuContribution> menuContributions() {
    return List.of();
  }

  /** Returns demo-signal contributions provided by this plugin. Default: empty. */
  default List<DemoSignalContribution> demoSignalContributions() {
    return List.of();
  }

  /**
   * Returns signal-source contributions provided by this plugin (e.g. microphone arrays, synthetic
   * generators, dataset replay sources). Default: empty.
   */
  default List<SignalSourceContribution> signalSourceContributions() {
    return List.of();
  }

  /**
   * Returns experiment contributions provided by this plugin (named, repeatable experimental
   * scenarios). Default: empty.
   */
  default List<ExperimentContribution> experimentContributions() {
    return List.of();
  }

  /**
   * Returns processing-pipeline contributions provided by this plugin (DSP + analysis stage
   * chains). Default: empty.
   */
  default List<PipelineContribution> pipelineContributions() {
    return List.of();
  }

  /**
   * Returns snapshot-stream contributions provided by this plugin (per-frame result streams).
   * Default: empty.
   */
  default List<SnapshotStreamContribution> snapshotStreamContributions() {
    return List.of();
  }

  /**
   * Returns UI-independent visualization contributions provided by this plugin (descriptions of
   * visual representations without a specific rendering technology). Default: empty.
   */
  default List<VisualizationContribution> visualizationContributions() {
    return List.of();
  }

  /**
   * Returns calibration contributions provided by this plugin (calibration procedures or persistent
   * calibration states). Default: empty.
   */
  default List<CalibrationContribution> calibrationContributions() {
    return List.of();
  }

  /**
   * Returns benchmark contributions provided by this plugin (quality metrics and evaluation
   * procedures). Default: empty.
   */
  default List<BenchmarkContribution> benchmarkContributions() {
    return List.of();
  }

  /**
   * Returns export-format contributions provided by this plugin (serialization capabilities such as
   * Markdown, CSV, JSON-lines). Default: empty.
   */
  default List<ExportFormatContribution> exportFormatContributions() {
    return List.of();
  }

  /**
   * Returns namespaced, versioned portable experiment-document sections. The host owns parsing,
   * limits, schema validation, canonical serialization and migration orchestration. Default: empty.
   */
  default List<ExperimentDocumentContribution> experimentDocumentContributions() {
    return List.of();
  }
}
