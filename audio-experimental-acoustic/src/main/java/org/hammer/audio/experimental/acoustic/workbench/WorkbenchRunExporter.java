package org.hammer.audio.experimental.acoustic.workbench;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.hammer.audio.experimental.acoustic.benchmark.BenchmarkReport;
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
  private static final String MARKDOWN_PIPE_SEPARATOR = " | ";
  private static final String PARAM_RESULT = "result";
  private static final String NA = "n/a";

  private WorkbenchRunExporter() {
    // utility class
  }

  /**
   * Produce a Markdown benchmark summary for the run result.
   *
   * <p>The summary is derived from the {@link BenchmarkReport} that was computed by comparing the
   * estimated tracking output against the scenario ground truth. If no benchmark report is
   * available (e.g. the run produced no snapshots or benchmark computation failed), a short
   * placeholder message is returned. Localization and frequency metric values that could not be
   * computed (evaluatedCount == 0) are rendered as {@code n/a}.
   *
   * @param result the run result to format
   * @return a Markdown-formatted benchmark summary string
   */
  public static String toBenchmarkMarkdown(WorkbenchRunResult result) {
    Objects.requireNonNull(result, PARAM_RESULT);
    BenchmarkReport report = result.benchmarkReport();
    if (report == null) {
      return "# Benchmark Report\n\n"
          + "*Not available — run produced no snapshots or benchmark computation failed.*\n";
    }
    StringBuilder sb = new StringBuilder(512);
    sb.append("# Benchmark Report — ")
        .append(result.scenario().name())
        .append(
            "\n\n> Comparison of estimated tracking output against scenario ground truth.\n"
                + "> All metrics are experimental.\n\n")
        .append(report.toMarkdownSummary())
        .append("\n\n## Localization Details\n\n| Metric | Value |\n|---|---|\n");
    appendRow(
        sb,
        "Mean position error (m)",
        formatMetric(FMT_4F, report.localization().meanDistanceErrorMeters()));
    appendRow(
        sb,
        "Median position error (m)",
        formatMetric(FMT_4F, report.localization().medianDistanceErrorMeters()));
    appendRow(
        sb,
        "Mean angular error (°)",
        formatMetric(FMT_4F, report.localization().meanAngularErrorDegrees()));
    appendRow(sb, "Evaluated samples", report.localization().evaluatedCount());
    appendRow(sb, "Skipped samples", report.localization().skippedCount());
    sb.append("\n## Frequency Details\n\n| Metric | Value |\n|---|---|\n");
    appendRow(
        sb,
        "Mean absolute error (Hz)",
        formatMetric(FMT_4F, report.frequency().meanAbsoluteErrorHz()));
    appendRow(
        sb,
        "Median absolute error (Hz)",
        formatMetric(FMT_4F, report.frequency().medianAbsoluteErrorHz()));
    appendRow(
        sb, "Mean relative error", formatMetric("%.6f", report.frequency().meanRelativeError()));
    appendRow(sb, "Evaluated samples", report.frequency().evaluatedCount());
    sb.append("\n## Tracking Quality\n\n| Metric | Value |\n|---|---|\n");
    appendRow(sb, "Expected sources", report.expectedSourceCount());
    appendRow(sb, "Snapshot count", report.snapshotCount());
    appendRow(sb, "Track continuity", formatMetric(FMT_4F, report.trackContinuity()));
    appendRow(sb, "ID stability", formatMetric(FMT_4F, report.idStability()));
    appendRow(sb, "False-positive rate", formatMetric(FMT_4F, report.falsePositiveRate()));
    appendRow(sb, "False-negative rate", formatMetric(FMT_4F, report.falseNegativeRate()));
    appendRow(sb, "Mean processing (ns)", report.meanProcessingNanos());
    return sb.toString();
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
          .append(MARKDOWN_PIPE_SEPARATOR)
          .append(String.format(Locale.ROOT, "%.1f", snap.sourceTimestampNanos() / 1_000_000.0))
          .append(MARKDOWN_PIPE_SEPARATOR)
          .append(snap.clusters().size())
          .append(MARKDOWN_PIPE_SEPARATOR)
          .append(snap.tracks().size())
          .append(MARKDOWN_PIPE_SEPARATOR)
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

  private static String formatMetric(String fmt, Double value) {
    return value == null ? NA : String.format(Locale.ROOT, fmt, value);
  }

  private static void appendRow(StringBuilder sb, String label, Object value) {
    sb.append("| ").append(label).append(MARKDOWN_PIPE_SEPARATOR).append(value).append(" |\n");
  }
}
