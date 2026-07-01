package org.hammer.audio.plugin;

/**
 * Describes an export-format contribution supplied by a plugin.
 *
 * <p>An export-format contribution represents a serialization capability that the plugin provides:
 * Markdown reports, CSV tables, JSON-lines streams, PNG images, binary blobs, etc. Plugins expose
 * their export formats through this interface so the host can list available export options and
 * invoke them without knowing the concrete serialization code.
 *
 * <p>This is a pure metadata/identity interface.
 */
public interface ExportFormatContribution {

  /** Stable identifier for this export format. */
  String id();

  /** Human-readable name shown in export dialogs (e.g. {@code "Markdown report"}). */
  String name();

  /**
   * Typical file-name extension for output produced by this format, without the leading dot (e.g.
   * {@code "md"}, {@code "csv"}, {@code "jsonl"}).
   */
  String fileExtension();

  /** Single-sentence description of what this export produces. */
  String description();
}
