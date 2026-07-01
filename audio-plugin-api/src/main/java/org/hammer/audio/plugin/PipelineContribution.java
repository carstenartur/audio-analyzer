package org.hammer.audio.plugin;

/**
 * Describes a processing-pipeline contribution supplied by a plugin.
 *
 * <p>A pipeline assembles a series of DSP and analysis stages that transform a raw signal into
 * structured output (snapshots, measurements, classifications). Plugins advertise their pipelines
 * here so the host can enumerate them, display documentation and wire them to sources without
 * depending on the concrete DSP types.
 *
 * <p>This is a pure metadata/identity interface. Actual pipeline construction and execution are
 * handled by plugin-internal code.
 */
public interface PipelineContribution {

  /** Stable identifier for this pipeline. */
  String id();

  /** Human-readable name shown in pipeline selectors. */
  String name();

  /** Single-sentence description of the processing performed by this pipeline. */
  String description();

  /**
   * Comma-separated list of stage names in execution order (informational, e.g. {@code
   * "peak-detection, TDOA, beamforming, Kalman tracking"}).
   */
  String stages();
}
