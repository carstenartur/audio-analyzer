package org.hammer.audio.plugin;

/**
 * Describes a signal-source contribution supplied by a plugin.
 *
 * <p>A signal source is anything that feeds raw signal data into an experiment: a microphone array,
 * a synthetic generator, a pre-recorded file, a network stream, or a dataset replay. Plugins
 * advertise their signal sources through this interface so the host can list, log and route them
 * without depending on the concrete audio-domain types.
 *
 * <p>This is a pure metadata/identity interface. Actual source instantiation is performed by plugin
 * code through its own factory or workbench entry points.
 */
public interface SignalSourceContribution {

  /** Stable identifier for this signal source. */
  String id();

  /** Human-readable name shown in source-selector UI. */
  String name();

  /** Single-sentence description of what this source provides. */
  String description();

  /**
   * Broad category of this source (e.g. {@code "microphone"}, {@code "synthetic"}, {@code
   * "recording"}, {@code "dataset"}).
   */
  String category();
}
