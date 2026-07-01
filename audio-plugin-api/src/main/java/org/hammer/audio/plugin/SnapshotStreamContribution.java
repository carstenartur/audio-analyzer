package org.hammer.audio.plugin;

/**
 * Describes a snapshot-stream contribution supplied by a plugin.
 *
 * <p>A snapshot stream is a sequence of immutable, per-frame analysis results produced by a running
 * pipeline (e.g. {@code TrackingSnapshot}, {@code SpectrumSnapshot}, occupancy readings). Plugins
 * advertise their snapshot streams through this interface so the host can subscribe, display and
 * record them without knowing the concrete snapshot type.
 *
 * <p>This is a pure metadata/identity interface. The concrete snapshot type and subscription
 * mechanism are handled by plugin-internal code.
 */
public interface SnapshotStreamContribution {

  /** Stable identifier for this snapshot stream. */
  String id();

  /** Human-readable name shown in stream selectors and log headers. */
  String name();

  /** Single-sentence description of what each snapshot in this stream represents. */
  String description();

  /**
   * Fully-qualified class name of the snapshot type produced by this stream (informational; the
   * host may use this to log type information but must not reflectively load the class).
   */
  String snapshotTypeName();
}
