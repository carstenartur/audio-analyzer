package org.hammer.audio.plugin;

/**
 * Describes a UI-independent visualization contribution supplied by a plugin.
 *
 * <p>A visualization contribution captures the <em>intent</em> of a visualization (room map, track
 * overlay, spectrum heatmap, occupancy grid) without referencing any rendering technology. The
 * concrete Swing / JavaFX / headless rendering is wired separately through a {@link
 * ViewContribution}. Plugins expose visualization metadata here so the host can enumerate supported
 * visual representations and route them to appropriate renderers.
 *
 * <p>This is a pure metadata/identity interface.
 */
public interface VisualizationContribution {

  /** Stable identifier for this visualization. */
  String id();

  /** Human-readable name shown in visualization selectors. */
  String name();

  /** Single-sentence description of what this visualization displays. */
  String description();

  /**
   * The kind of rendering this visualization requires (e.g. {@code "2d-spatial"}, {@code
   * "time-series"}, {@code "heatmap"}, {@code "scatter"}, {@code "table"}).
   */
  String renderKind();
}
