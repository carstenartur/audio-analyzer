/**
 * Stable plugin API for the Audio Analyzer host application.
 *
 * <p>This package defines the contracts that plugins implement to extend the host application with
 * additional analyses, demo signals, views and menu entries. The API intentionally does not depend
 * on JavaSound or any concrete audio-analysis module ({@code audio-core}, {@code audio-dsp}, {@code
 * audio-acquisition}, {@code audio-geometry}). {@link org.hammer.audio.plugin.ViewContribution}
 * references {@link javax.swing.JComponent} from the JDK so the host can render plugin views
 * generically; no third-party UI framework is bundled.
 *
 * <p>The generalized workbench API exposes the following contribution types so that any
 * signal-processing or acoustic-measurement workflow can plug in using the same infrastructure:
 *
 * <ul>
 *   <li>{@link org.hammer.audio.plugin.SignalSourceContribution} — signal sources (microphone
 *       array, synthetic generator, recording, dataset replay)
 *   <li>{@link org.hammer.audio.plugin.ExperimentContribution} — named, repeatable experiments or
 *       scenarios
 *   <li>{@link org.hammer.audio.plugin.PipelineContribution} — DSP + analysis stage chains
 *   <li>{@link org.hammer.audio.plugin.SnapshotStreamContribution} — per-frame result streams
 *   <li>{@link org.hammer.audio.plugin.VisualizationContribution} — UI-independent visualization
 *       descriptions
 *   <li>{@link org.hammer.audio.plugin.CalibrationContribution} — calibration procedures and
 *       persistent calibration states
 *   <li>{@link org.hammer.audio.plugin.BenchmarkContribution} — quality metrics and evaluation
 *       procedures
 *   <li>{@link org.hammer.audio.plugin.ExportFormatContribution} — serialization capabilities
 *       (Markdown, CSV, JSON-lines, …)
 * </ul>
 *
 * <p>Plugins register their {@link org.hammer.audio.plugin.AudioAnalyzerPlugin} implementations via
 * the Java {@link java.util.ServiceLoader} mechanism, i.e. by adding a {@code
 * META-INF/services/org.hammer.audio.plugin.AudioAnalyzerPlugin} file to the plugin JAR.
 */
package org.hammer.audio.plugin;
