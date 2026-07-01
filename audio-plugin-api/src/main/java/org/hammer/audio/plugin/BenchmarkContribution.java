package org.hammer.audio.plugin;

/**
 * Describes a benchmark contribution supplied by a plugin.
 *
 * <p>A benchmark contribution represents a metric or evaluation procedure that the plugin can
 * execute to measure algorithm quality (e.g. localization position error, classification accuracy,
 * Doppler velocity error, processing latency). Plugins expose benchmarks through this interface so
 * the host can enumerate, run and compare results without depending on the concrete metric
 * implementations.
 *
 * <p>This is a pure metadata/identity interface.
 */
public interface BenchmarkContribution {

  /** Stable identifier for this benchmark. */
  String id();

  /** Human-readable name shown in benchmark dashboards. */
  String name();

  /** Single-sentence description of what this benchmark measures. */
  String description();

  /**
   * Unit of the primary result value (e.g. {@code "metres"}, {@code "percent"}, {@code
   * "milliseconds"}, {@code "dimensionless"}).
   */
  String unit();
}
