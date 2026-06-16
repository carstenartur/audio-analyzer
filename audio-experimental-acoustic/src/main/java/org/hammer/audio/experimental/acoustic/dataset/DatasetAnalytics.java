package org.hammer.audio.experimental.acoustic.dataset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Summary analytics computed from a {@link DatasetManifest}.
 *
 * <p>This class computes descriptive statistics over the recordings in a dataset, including label
 * distribution, sample-rate distribution, and duration distribution. Use {@link
 * #compute(DatasetManifest)} to build an analytics snapshot, and {@link #toMarkdownReport()} to
 * render a human-readable summary.
 *
 * @param datasetName the name of the dataset
 * @param recordingCount total number of recordings in the manifest
 * @param labelDistribution count of recordings per unique value of each metadata label key
 * @param sampleRateCounts count of recordings per distinct sample rate (Hz, rounded to integer)
 * @param durationStats descriptive statistics over clip durations (seconds)
 */
@SuppressWarnings({"PMD.UseConcurrentHashMap", "PMD.DanglingJavadoc"})
public record DatasetAnalytics(
    String datasetName,
    int recordingCount,
    Map<String, Map<String, Integer>> labelDistribution,
    Map<Integer, Integer> sampleRateCounts,
    DistributionStats durationStats) {

  /** Validate and defensively copy all fields. */
  public DatasetAnalytics {
    Objects.requireNonNull(datasetName, "datasetName");
    Objects.requireNonNull(labelDistribution, "labelDistribution");
    Objects.requireNonNull(sampleRateCounts, "sampleRateCounts");
    Objects.requireNonNull(durationStats, "durationStats");
    if (datasetName.isBlank()) {
      throw new IllegalArgumentException("datasetName must not be blank");
    }
    if (recordingCount < 0) {
      throw new IllegalArgumentException("recordingCount must be >= 0");
    }
    Map<String, Map<String, Integer>> labelCopy = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, Integer>> entry : labelDistribution.entrySet()) {
      labelCopy.put(entry.getKey(), Map.copyOf(entry.getValue()));
    }
    labelDistribution = Map.copyOf(labelCopy);
    sampleRateCounts = Map.copyOf(sampleRateCounts);
  }

  /**
   * Compute analytics from the given manifest.
   *
   * @param manifest imported dataset manifest; must not be {@code null}
   * @return analytics snapshot; never {@code null}
   */
  public static DatasetAnalytics compute(DatasetManifest manifest) {
    Objects.requireNonNull(manifest, "manifest");
    List<DatasetRecording> recordings = manifest.recordings();
    Map<String, Map<String, Integer>> labelDist = new LinkedHashMap<>();
    Map<Integer, Integer> sampleRateCounts = new LinkedHashMap<>();
    List<Double> durations = new ArrayList<>(recordings.size());
    for (DatasetRecording rec : recordings) {
      for (Map.Entry<String, String> label : rec.labels().entrySet()) {
        labelDist
            .computeIfAbsent(label.getKey(), k -> new LinkedHashMap<>())
            .merge(label.getValue(), 1, Integer::sum);
      }
      int srBucket = (int) Math.round(rec.sampleRateHz());
      sampleRateCounts.merge(srBucket, 1, Integer::sum);
      durations.add(rec.durationSeconds());
    }
    DistributionStats durationStats = DistributionStats.of(durations);
    return new DatasetAnalytics(
        manifest.descriptor().name(),
        recordings.size(),
        labelDist,
        sampleRateCounts,
        durationStats);
  }

  /**
   * Render a compact Markdown summary of this analytics snapshot.
   *
   * @return Markdown text; never {@code null}
   */
  @SuppressWarnings({"PMD.ConsecutiveAppendsShouldReuse", "PMD.ConsecutiveLiteralAppends"})
  public String toMarkdownReport() {
    StringBuilder sb = new StringBuilder(512);
    sb.append("# Dataset Analytics\n\n");
    sb.append("- Dataset: ").append(datasetName).append('\n');
    sb.append("- Recordings: ").append(recordingCount).append('\n');
    sb.append('\n');

    sb.append("## Duration Distribution (seconds)\n\n");
    sb.append(durationStats.toMarkdown()).append('\n');

    if (!sampleRateCounts.isEmpty()) {
      sb.append("## Sample Rate Distribution\n\n");
      sb.append("| Sample rate (Hz) | Count |\n|---:|---:|\n");
      for (Map.Entry<Integer, Integer> entry : sampleRateCounts.entrySet()) {
        sb.append("| ")
            .append(entry.getKey())
            .append(" | ")
            .append(entry.getValue())
            .append(" |\n");
      }
      sb.append('\n');
    }

    if (!labelDistribution.isEmpty()) {
      sb.append("## Label Distribution\n\n");
      for (Map.Entry<String, Map<String, Integer>> labelEntry : labelDistribution.entrySet()) {
        sb.append("### ").append(labelEntry.getKey()).append("\n\n");
        sb.append("| Value | Count |\n|---|---:|\n");
        for (Map.Entry<String, Integer> valueEntry : labelEntry.getValue().entrySet()) {
          sb.append("| ")
              .append(valueEntry.getKey())
              .append(" | ")
              .append(valueEntry.getValue())
              .append(" |\n");
        }
        sb.append('\n');
      }
    }

    return sb.toString();
  }

  /**
   * Descriptive statistics over a series of {@code double} values.
   *
   * @param count number of values
   * @param min minimum value
   * @param max maximum value
   * @param mean arithmetic mean
   * @param stddev population standard deviation
   */
  public record DistributionStats(int count, double min, double max, double mean, double stddev) {

    /**
     * Compute stats over a list of doubles. Returns a zero-valued instance when the list is empty.
     */
    public static DistributionStats of(List<Double> values) {
      Objects.requireNonNull(values, "values");
      if (values.isEmpty()) {
        return new DistributionStats(0, 0.0, 0.0, 0.0, 0.0);
      }
      double min = values.get(0);
      double max = values.get(0);
      double sum = 0.0;
      for (double v : values) {
        if (v < min) {
          min = v;
        }
        if (v > max) {
          max = v;
        }
        sum += v;
      }
      double mean = sum / values.size();
      double variance = 0.0;
      for (double v : values) {
        double diff = v - mean;
        variance += diff * diff;
      }
      double stddev = Math.sqrt(variance / values.size());
      return new DistributionStats(values.size(), min, max, mean, stddev);
    }

    /** Render this distribution as a compact Markdown snippet (bullet list). */
    public String toMarkdown() {
      if (count == 0) {
        return "- No data\n";
      }
      return String.format(
          Locale.ROOT,
          "- Count: %d%n- Min: %.3f%n- Max: %.3f%n- Mean: %.3f%n- Std dev: %.3f%n",
          count,
          min,
          max,
          mean,
          stddev);
    }
  }
}
