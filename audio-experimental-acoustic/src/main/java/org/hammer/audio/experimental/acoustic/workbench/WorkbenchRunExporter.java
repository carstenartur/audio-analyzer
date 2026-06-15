package org.hammer.audio.experimental.acoustic.workbench;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.hammer.audio.experimental.acoustic.tracking.FrequencyCluster;
import org.hammer.audio.experimental.acoustic.tracking.TrackedSource;
import org.hammer.audio.experimental.acoustic.tracking.TrackingSnapshot;

/**
 * Exports a {@link WorkbenchRunResult} to Markdown, CSV or JSON text.
 *
 * <p>All methods are pure functions that return a {@link String}; no I/O is performed here. The
 * caller is responsible for writing the result to disk or a UI component.
 */
public final class WorkbenchRunExporter {

  private static final String FMT_4F = "%.4f";
  private static final String PIPE_SEP = " | ";
  private static final String PARAM_RESULT = "result";

  private WorkbenchRunExporter() {
    // utility class
  }

  /**
   * Produce a Markdown summary of the run result.
   *
   * <p>The summary includes: scenario metadata, parameter overview, overall statistics and a
   * per-block table of frame index, cluster count, track count and processing time.
   *
   * @param result the run result to format
   * @return a Markdown-formatted summary string
   */
  public static String toMarkdown(WorkbenchRunResult result) {
    Objects.requireNonNull(result, PARAM_RESULT);
    StringBuilder sb = new StringBuilder(2048);
    sb.append(
            "# Acoustic Localization Workbench — Run Summary\n\n"
                + "> **Experimental research output.** "
                + "This is not a production localization result.\n\n"
                + "## Scenario\n\n"
                + "- **Name:** ")
        .append(result.scenario().name())
        .append("\n- **Room:** ")
        .append(
            String.format(
                Locale.ROOT,
                "%.1f × %.1f m",
                result.scenario().room().widthMeters(),
                result.scenario().room().heightMeters()))
        .append("\n- **Emitters:** ")
        .append(result.scenario().emitters().size())
        .append("\n- **Sample rate:** ")
        .append(String.format(Locale.ROOT, "%.0f Hz", (double) result.scenario().sampleRate()))
        .append("\n- **Duration:** ")
        .append(String.format(Locale.ROOT, "%.2f s", result.scenario().durationSeconds()))
        .append("\n\n## Parameters\n\n| Parameter | Value |\n|---|---|\n");

    WorkbenchParameters p = result.parameters();
    appendRow(sb, "Block size", p.blockSize());
    appendRow(sb, "FFT size", p.fftSize());
    appendRow(sb, "Max peaks", p.maxPeaks());
    appendRow(sb, "Min SNR", String.format(Locale.ROOT, "%.1f", p.minSnr()));
    appendRow(
        sb, "Band", String.format(Locale.ROOT, "%.0f – %.0f Hz", p.bandMinHz(), p.bandMaxHz()));
    appendRow(
        sb,
        "Clustering tolerance",
        String.format(Locale.ROOT, "%.0f Hz", p.clusteringToleranceHz()));
    appendRow(sb, "Grid steps", p.candidateGridSteps());
    appendRow(sb, "TDOA estimator", p.tdoaEstimatorType());
    appendRow(
        sb,
        "Tracker freq. match",
        String.format(Locale.ROOT, "%.0f Hz", p.trackerFrequencyMatchHz()));

    sb.append("\n## Statistics\n\n| Metric | Value |\n|---|---|\n");
    appendRow(sb, "Blocks processed", result.blockCount());
    appendRow(sb, "Any tracked", result.anyTracked());
    appendRow(sb, "Max tracks in a frame", result.maxTracksInAnyFrame());
    appendRow(sb, "Distinct track IDs", result.distinctTrackCount());
    appendRow(
        sb,
        "Avg processing / block",
        String.format(Locale.ROOT, "%.2f µs", result.averageProcessingNanosPerBlock() / 1_000.0));
    appendRow(
        sb,
        "Max processing / block",
        String.format(Locale.ROOT, "%.2f µs", result.maxProcessingNanosPerBlock() / 1_000.0));

    sb.append(
        "\n## Frame-by-frame summary\n\n"
            + "| Frame | Time (ms) | Clusters | Tracks | Proc. (µs) |\n"
            + "|---|---|---|---|---|\n");
    for (TrackingSnapshot snap : result.snapshots()) {
      sb.append("| ")
          .append(snap.sourceFrameIndex())
          .append(PIPE_SEP)
          .append(String.format(Locale.ROOT, "%.1f", snap.sourceTimestampNanos() / 1_000_000.0))
          .append(PIPE_SEP)
          .append(snap.clusters().size())
          .append(PIPE_SEP)
          .append(snap.tracks().size())
          .append(PIPE_SEP)
          .append(String.format(Locale.ROOT, "%.1f", snap.processingNanos() / 1_000.0))
          .append(" |\n");
    }
    return sb.toString();
  }

  /**
   * Produce a CSV export of every tracked source observation across all frames.
   *
   * <p>Each row represents one {@link TrackedSource} in one frame. The CSV uses {@code ,} as
   * separator and includes a header row.
   *
   * @param result the run result to format
   * @return a CSV-formatted string
   */
  public static String toCsv(WorkbenchRunResult result) {
    Objects.requireNonNull(result, PARAM_RESULT);
    StringBuilder sb = new StringBuilder(1024);
    sb.append(
        "frameIndex,timestampNs,trackId,frequencyHz,observedFrequencyHz,"
            + "posX,posY,velX,velY,confidence,observations,processingNs\n");
    for (TrackingSnapshot snap : result.snapshots()) {
      for (TrackedSource track : snap.tracks()) {
        sb.append(snap.sourceFrameIndex())
            .append(',')
            .append(snap.sourceTimestampNanos())
            .append(',')
            .append(track.id())
            .append(',')
            .append(String.format(Locale.ROOT, "%.3f", track.frequencyHz()))
            .append(',')
            .append(String.format(Locale.ROOT, "%.3f", track.observedFrequencyHz()))
            .append(',')
            .append(String.format(Locale.ROOT, FMT_4F, track.positionMeters().x()))
            .append(',')
            .append(String.format(Locale.ROOT, FMT_4F, track.positionMeters().y()))
            .append(',')
            .append(String.format(Locale.ROOT, FMT_4F, track.velocityMetersPerSecond().x()))
            .append(',')
            .append(String.format(Locale.ROOT, FMT_4F, track.velocityMetersPerSecond().y()))
            .append(',')
            .append(String.format(Locale.ROOT, FMT_4F, track.confidence()))
            .append(',')
            .append(track.observationCount())
            .append(',')
            .append(snap.processingNanos())
            .append('\n');
      }
    }
    return sb.toString();
  }

  /**
   * Produce a JSON-lines export of all snapshots (one JSON object per line).
   *
   * <p>Each line is a self-contained JSON object describing one frame: its index, timestamp,
   * detected clusters and tracked sources. No external JSON library is required; the output is
   * hand-formatted.
   *
   * @param result the run result to format
   * @return a JSON-lines formatted string
   */
  public static String toJsonLines(WorkbenchRunResult result) {
    Objects.requireNonNull(result, PARAM_RESULT);
    StringBuilder sb = new StringBuilder(512);
    for (TrackingSnapshot snap : result.snapshots()) {
      sb.append("{\"frameIndex\":")
          .append(snap.sourceFrameIndex())
          .append(",\"timestampNs\":")
          .append(snap.sourceTimestampNanos())
          .append(",\"processingNs\":")
          .append(snap.processingNanos())
          .append(",\"clusters\":[");
      appendClustersJson(sb, snap.clusters());
      sb.append("],\"tracks\":[");
      appendTracksJson(sb, snap.tracks());
      sb.append("]}\n");
    }
    return sb.toString();
  }

  private static void appendClustersJson(StringBuilder sb, List<FrequencyCluster> clusters) {
    for (int i = 0; i < clusters.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      FrequencyCluster c = clusters.get(i);
      sb.append(
          String.format(
              Locale.ROOT,
              "{\"freqHz\":%.3f,\"magnitude\":%.4f,\"channels\":%d}",
              c.centerFrequencyHz(),
              c.totalMagnitude(),
              c.channelCount()));
    }
  }

  private static void appendTracksJson(StringBuilder sb, List<TrackedSource> tracks) {
    for (int i = 0; i < tracks.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      TrackedSource t = tracks.get(i);
      sb.append(
          String.format(
              Locale.ROOT,
              "{\"id\":%d,\"freqHz\":%.3f,\"x\":%.4f,\"y\":%.4f,"
                  + "\"confidence\":%.4f,\"observations\":%d}",
              t.id(),
              t.frequencyHz(),
              t.positionMeters().x(),
              t.positionMeters().y(),
              t.confidence(),
              t.observationCount()));
    }
  }

  /**
   * Collect the distinct track IDs across all snapshots in order of first appearance.
   *
   * @param result the run result
   * @return list of track IDs in first-seen order
   */
  public static List<Integer> observedTrackIds(WorkbenchRunResult result) {
    Objects.requireNonNull(result, PARAM_RESULT);
    Set<Integer> seen = new LinkedHashSet<>();
    for (TrackingSnapshot snap : result.snapshots()) {
      for (TrackedSource track : snap.tracks()) {
        seen.add(track.id());
      }
    }
    return new ArrayList<>(seen);
  }

  private static void appendRow(StringBuilder sb, String label, Object value) {
    sb.append("| ").append(label).append(PIPE_SEP).append(value).append(" |\n");
  }
}
