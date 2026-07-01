package org.hammer.audio.plugin;

/**
 * Describes an experiment (or scenario) contribution supplied by a plugin.
 *
 * <p>An experiment is a named, repeatable unit of work that a plugin can execute: a simulation
 * scenario, a dataset-replay trial, a calibration run or a benchmarking sweep. Plugins expose their
 * experiments through this interface so the host can enumerate and invoke them without knowing the
 * concrete domain types.
 *
 * <p>This is a pure metadata/identity interface. Actual execution is triggered via the plugin's own
 * workbench or runner entry points (e.g. through a {@link ViewContribution} or headless runner).
 */
public interface ExperimentContribution {

  /** Stable identifier for this experiment. */
  String id();

  /** Human-readable name shown in scenario selectors and logs. */
  String name();

  /** Single-sentence description of what this experiment tests or demonstrates. */
  String description();

  /**
   * Broad category grouping related experiments (e.g. {@code "localization"}, {@code "resonance"},
   * {@code "calibration"}, {@code "benchmarking"}).
   */
  String category();
}
