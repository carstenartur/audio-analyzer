package org.hammer.audio.experimental.acoustic.wingbeat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.experimental.acoustic.FrequencyBand;
import org.hammer.audio.experimental.acoustic.SpectralPeak;
import org.hammer.audio.experimental.acoustic.WingbeatFrequencyTracker;
import org.hammer.audio.experimental.acoustic.dataset.DatasetAnalytics;
import org.hammer.audio.experimental.acoustic.dataset.DatasetAudioLoader;
import org.hammer.audio.experimental.acoustic.dataset.DatasetManifest;
import org.hammer.audio.experimental.acoustic.dataset.DatasetRecording;
import org.hammer.audio.experimental.acoustic.dataset.FeatureHistogram;
import org.hammer.audio.experimental.acoustic.tracking.TrackedSource;
import org.hammer.audio.geometry.Vector2;
import org.hammer.audio.geometry.Vector3;

/**
 * End-to-end workflow for evaluating imported dataset recordings with the wingbeat classifier
 * pipeline.
 */
@SuppressWarnings("PMD.CouplingBetweenObjects")
public final class DatasetWingbeatEvaluationWorkflow {

  private static final int DEFAULT_FFT_SIZE = 2048;
  private static final FrequencyBand DEFAULT_BAND = new FrequencyBand(300.0, 800.0);

  private final DatasetAudioLoader audioLoader;
  private final WingbeatFrequencyTracker frequencyTracker;
  private final WingbeatFeatureExtractor featureExtractor;
  private final GroundTruthLabelResolver labelResolver;

  /** Create a workflow using default HumBugDB-friendly analysis parameters. */
  public DatasetWingbeatEvaluationWorkflow() {
    this(
        new DatasetAudioLoader(),
        new WingbeatFrequencyTracker(DEFAULT_FFT_SIZE, DEFAULT_BAND),
        new WingbeatFeatureExtractor(DEFAULT_FFT_SIZE, DEFAULT_BAND),
        DatasetWingbeatEvaluationWorkflow::defaultGroundTruthLabel);
  }

  DatasetWingbeatEvaluationWorkflow(
      DatasetAudioLoader audioLoader,
      WingbeatFrequencyTracker frequencyTracker,
      WingbeatFeatureExtractor featureExtractor,
      GroundTruthLabelResolver labelResolver) {
    this.audioLoader = Objects.requireNonNull(audioLoader, "audioLoader");
    this.frequencyTracker = Objects.requireNonNull(frequencyTracker, "frequencyTracker");
    this.featureExtractor = Objects.requireNonNull(featureExtractor, "featureExtractor");
    this.labelResolver = Objects.requireNonNull(labelResolver, "labelResolver");
  }

  /**
   * Return deterministic provenance text for the default feature-extraction configuration used by
   * this workflow.
   */
  public static String defaultFeatureExtractionProvenance() {
    return String.format(
        Locale.ROOT,
        "WingbeatFrequencyTracker fft=%d band=[%.1f, %.1f] Hz; WingbeatFeatureExtractor fft=%d band=[%.1f, %.1f] Hz",
        DEFAULT_FFT_SIZE,
        DEFAULT_BAND.lowHz(),
        DEFAULT_BAND.highHz(),
        DEFAULT_FFT_SIZE,
        DEFAULT_BAND.lowHz(),
        DEFAULT_BAND.highHz());
  }

  /**
   * Build a wingbeat evaluation dataset from an imported manifest.
   *
   * @param manifest imported dataset manifest
   * @return wingbeat dataset for classifier evaluation
   * @throws IOException when audio files cannot be loaded
   */
  public WingbeatDataset buildDataset(DatasetManifest manifest) throws IOException {
    Objects.requireNonNull(manifest, "manifest");
    if (manifest.recordings().isEmpty()) {
      throw new IllegalArgumentException(
          "manifest must contain at least one recording to build a dataset");
    }
    List<LabelledRecording> entries = new ArrayList<>(manifest.recordings().size());
    for (DatasetRecording recording : manifest.recordings()) {
      RecordingAnalysis analysis = analyzeRecording(manifest, recording, null);
      entries.add(
          new LabelledRecording(
              recording.recordingId(),
              describe(recording),
              analysis.groundTruthLabel(),
              analysis.features()));
    }
    return new WingbeatDataset(manifest.descriptor().name(), entries);
  }

  /**
   * Evaluate a classifier against all recordings in the imported manifest.
   *
   * @param manifest imported dataset manifest
   * @param classifier classifier to evaluate
   * @return evaluation summary
   * @throws IOException when audio files cannot be loaded
   */
  public WingbeatDataset.Evaluation evaluate(
      DatasetManifest manifest, WingbeatClassifier classifier) throws IOException {
    return buildDataset(manifest).evaluate(classifier);
  }

  /**
   * Analyze all recordings in the manifest and return a list of per-recording results.
   *
   * <p>This is useful for computing feature distribution statistics across the full dataset.
   *
   * @param manifest imported dataset manifest
   * @param classifier optional classifier; may be {@code null}
   * @return list of recording analyses in manifest order; never {@code null}
   * @throws IOException when any audio file cannot be loaded
   */
  public List<RecordingAnalysis> analyzeAll(DatasetManifest manifest, WingbeatClassifier classifier)
      throws IOException {
    Objects.requireNonNull(manifest, "manifest");
    List<RecordingAnalysis> results = new ArrayList<>(manifest.recordings().size());
    for (DatasetRecording recording : manifest.recordings()) {
      results.add(analyzeRecording(manifest, recording, classifier));
    }
    return results;
  }

  /**
   * Render a Markdown report summarising dominant-frequency and harmonic distributions across all
   * analyzed recordings.
   *
   * @param analyses list of recording analyses; must not be {@code null}
   * @return Markdown feature distribution summary
   */
  @SuppressWarnings({"PMD.ConsecutiveAppendsShouldReuse", "PMD.ConsecutiveLiteralAppends"})
  public static String toFeatureDistributionMarkdown(List<RecordingAnalysis> analyses) {
    Objects.requireNonNull(analyses, "analyses");
    if (analyses.isEmpty()) {
      return "# Feature Distribution\n\n*No recordings to analyze.*\n";
    }
    double[] freqs =
        analyses.stream().mapToDouble(a -> a.features().fundamentalFrequencyHz()).toArray();
    double[] snrs = analyses.stream().mapToDouble(a -> a.features().signalToNoiseRatio()).toArray();
    List<Double> h2ratios = new ArrayList<>();
    for (RecordingAnalysis a : analyses) {
      List<Double> ratios = a.features().harmonicRatios();
      if (!ratios.isEmpty()) {
        h2ratios.add(ratios.get(0));
      }
    }
    StringBuilder sb = new StringBuilder(512);
    sb.append("# Feature Distribution\n\n");
    sb.append("- Recordings analysed: ").append(analyses.size()).append('\n');
    sb.append('\n');
    sb.append("## Dominant Frequency (Hz)\n\n");
    appendStats(sb, freqs);
    sb.append("\n## Signal-to-Noise Ratio\n\n");
    appendStats(sb, snrs);
    if (!h2ratios.isEmpty()) {
      sb.append("\n## 2nd Harmonic / Fundamental Ratio\n\n");
      appendStats(sb, h2ratios.stream().mapToDouble(Double::doubleValue).toArray());
    }
    return sb.toString();
  }

  /**
   * Compute histograms for the dominant frequency, SNR, 2nd-harmonic ratio and duration
   * distributions across all analyzed recordings.
   *
   * <p>Bucket boundaries are determined automatically from the observed value range (Sturges'
   * rule). An empty list of analyses yields histograms with no buckets. Harmonic-ratio histogram is
   * omitted when no recording provides harmonic data.
   *
   * @param analyses list of recording analyses; must not be {@code null}
   * @return list of four histograms in order: dominant frequency, SNR, 2nd harmonic ratio,
   *     duration; never {@code null}
   */
  public static List<FeatureHistogram> computeHistograms(List<RecordingAnalysis> analyses) {
    Objects.requireNonNull(analyses, "analyses");
    double[] freqs =
        analyses.stream().mapToDouble(a -> a.features().fundamentalFrequencyHz()).toArray();
    double[] snrs = analyses.stream().mapToDouble(a -> a.features().signalToNoiseRatio()).toArray();
    double[] durations =
        analyses.stream().mapToDouble(a -> a.features().trackDurationSeconds()).toArray();
    double[] h2ratios =
        analyses.stream()
            .filter(a -> !a.features().harmonicRatios().isEmpty())
            .mapToDouble(a -> a.features().harmonicRatios().get(0))
            .toArray();
    return List.of(
        FeatureHistogram.of("Dominant Frequency (Hz)", freqs),
        FeatureHistogram.of("Signal-to-Noise Ratio", snrs),
        FeatureHistogram.of("2nd Harmonic Ratio", h2ratios),
        FeatureHistogram.of("Duration (s)", durations));
  }

  /**
   * Render a Markdown section containing all feature histograms.
   *
   * @param histograms histograms to render; must not be {@code null}
   * @return Markdown text; never {@code null}
   */
  public static String toHistogramMarkdown(List<FeatureHistogram> histograms) {
    Objects.requireNonNull(histograms, "histograms");
    StringBuilder sb = new StringBuilder(512);
    sb.append("# Feature Histograms\n\n");
    for (FeatureHistogram h : histograms) {
      sb.append(h.toMarkdown());
    }
    return sb.toString();
  }

  /**
   * Compare feature distributions between a synthetic corpus and a real-recording corpus.
   *
   * <p>For each of the key features — dominant frequency, SNR, 2nd-harmonic ratio and duration —
   * this method computes descriptive statistics for both corpora and returns a comparison record
   * that quantifies the absolute and relative differences between the two means.
   *
   * <p>Either list may be empty; in that case the corresponding statistics will have {@code count =
   * 0} and all numeric fields equal to {@code 0}.
   *
   * @param syntheticAnalyses analyses of synthetic recordings; must not be {@code null}
   * @param realAnalyses analyses of real recordings; must not be {@code null}
   * @return list of four comparisons in order: dominant frequency, SNR, 2nd harmonic ratio,
   *     duration; never {@code null}
   */
  public static List<FeatureDistributionComparison> compareDatasets(
      List<RecordingAnalysis> syntheticAnalyses, List<RecordingAnalysis> realAnalyses) {
    Objects.requireNonNull(syntheticAnalyses, "syntheticAnalyses");
    Objects.requireNonNull(realAnalyses, "realAnalyses");
    return List.of(
        buildComparison(
            "Dominant Frequency (Hz)",
            syntheticAnalyses.stream()
                .map(a -> a.features().fundamentalFrequencyHz())
                .collect(java.util.stream.Collectors.toList()),
            realAnalyses.stream()
                .map(a -> a.features().fundamentalFrequencyHz())
                .collect(java.util.stream.Collectors.toList())),
        buildComparison(
            "Signal-to-Noise Ratio",
            syntheticAnalyses.stream()
                .map(a -> a.features().signalToNoiseRatio())
                .collect(java.util.stream.Collectors.toList()),
            realAnalyses.stream()
                .map(a -> a.features().signalToNoiseRatio())
                .collect(java.util.stream.Collectors.toList())),
        buildComparison(
            "2nd Harmonic Ratio",
            syntheticAnalyses.stream()
                .filter(a -> !a.features().harmonicRatios().isEmpty())
                .map(a -> a.features().harmonicRatios().get(0))
                .collect(java.util.stream.Collectors.toList()),
            realAnalyses.stream()
                .filter(a -> !a.features().harmonicRatios().isEmpty())
                .map(a -> a.features().harmonicRatios().get(0))
                .collect(java.util.stream.Collectors.toList())),
        buildComparison(
            "Duration (s)",
            syntheticAnalyses.stream()
                .map(a -> a.features().trackDurationSeconds())
                .collect(java.util.stream.Collectors.toList()),
            realAnalyses.stream()
                .map(a -> a.features().trackDurationSeconds())
                .collect(java.util.stream.Collectors.toList())));
  }

  private static FeatureDistributionComparison buildComparison(
      String featureName, List<Double> syntheticValues, List<Double> realValues) {
    return FeatureDistributionComparison.of(
        featureName,
        DatasetAnalytics.DistributionStats.of(syntheticValues),
        DatasetAnalytics.DistributionStats.of(realValues));
  }

  /**
   * Render a Markdown comparison report from a list of per-feature comparisons.
   *
   * @param comparisons list of comparisons to render; must not be {@code null}
   * @return Markdown text; never {@code null}
   */
  public static String toComparisonMarkdown(List<FeatureDistributionComparison> comparisons) {
    Objects.requireNonNull(comparisons, "comparisons");
    StringBuilder sb = new StringBuilder(512);
    sb.append("# Synthetic vs Real Dataset Comparison\n\n");
    if (comparisons.isEmpty()) {
      sb.append("*No features to compare.*\n");
      return sb.toString();
    }
    sb.append(
        "Differences are computed as |realMean − syntheticMean|."
            + " Relative difference is normalised to the synthetic mean.\n\n");
    for (FeatureDistributionComparison c : comparisons) {
      sb.append(c.toMarkdown());
    }
    return sb.toString();
  }

  @SuppressWarnings("PMD.ConsecutiveAppendsShouldReuse")
  private static void appendStats(StringBuilder sb, double[] values) {
    if (values.length == 0) {
      sb.append("- No data\n");
      return;
    }
    double min = values[0];
    double max = values[0];
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
    double mean = sum / values.length;
    double variance = 0.0;
    for (double v : values) {
      double diff = v - mean;
      variance += diff * diff;
    }
    double stddev = Math.sqrt(variance / values.length);
    sb.append(String.format(Locale.ROOT, "- Min: %.2f%n", min));
    sb.append(String.format(Locale.ROOT, "- Max: %.2f%n", max));
    sb.append(String.format(Locale.ROOT, "- Mean: %.2f%n", mean));
    sb.append(String.format(Locale.ROOT, "- Std dev: %.2f%n", stddev));
  }

  /**
   * Analyze one imported recording by loading audio, extracting a dominant frequency, deriving a
   * feature vector and optionally classifying it.
   *
   * @param manifest imported dataset manifest
   * @param recording recording entry to analyze
   * @param classifier optional classifier; may be {@code null}
   * @return per-recording analysis
   * @throws IOException when the audio file cannot be loaded
   */
  public RecordingAnalysis analyzeRecording(
      DatasetManifest manifest, DatasetRecording recording, WingbeatClassifier classifier)
      throws IOException {
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(recording, "recording");
    Path audioPath = resolveAudioPath(manifest, recording);
    AudioBlock block = audioLoader.load(audioPath);
    SpectralPeak peak = frequencyTracker.track(block, 0);
    TrackedSource source =
        new TrackedSource(
            0,
            peak.frequencyHz(),
            peak.frequencyHz(),
            Vector2.ZERO,
            Vector2.ZERO,
            Vector3.ZERO,
            0.0,
            0.0,
            peak.confidence(),
            0L,
            1);
    double decodedDurationSeconds = block.frames() / block.format().sampleRate();
    WingbeatFeatureVector features =
        featureExtractor.extract(
            source,
            block,
            0,
            decodedDurationSeconds > 0.0 ? decodedDurationSeconds : recording.durationSeconds());
    String groundTruthLabel = labelResolver.resolve(recording);
    ClassificationResult classificationResult =
        classifier == null ? null : classifier.classify(features);
    return new RecordingAnalysis(
        recording, audioPath, block, groundTruthLabel, features, classificationResult);
  }

  /**
   * Render a compact Markdown report for one dataset evaluation run.
   *
   * <p>The report includes overall accuracy, per-label precision/recall, and a confusion matrix.
   *
   * @param evaluation evaluation summary
   * @return Markdown summary
   */
  @SuppressWarnings({
    "PMD.ConsecutiveAppendsShouldReuse",
    "PMD.ConsecutiveLiteralAppends",
    "PMD.AvoidDuplicateLiterals",
    "PMD.NPathComplexity"
  })
  public static String toMarkdownReport(WingbeatDataset.Evaluation evaluation) {
    Objects.requireNonNull(evaluation, "evaluation");
    StringBuilder sb = new StringBuilder(512);
    sb.append("# Imported Dataset Evaluation\n\n");
    sb.append("- Dataset: ").append(evaluation.datasetName()).append('\n');
    sb.append("- Samples: ").append(evaluation.sampleCount()).append('\n');
    sb.append("- Evaluated (known GT): ").append(evaluation.evaluatedSampleCount()).append('\n');
    sb.append("- GT unknown: ").append(evaluation.groundTruthUnknownCount()).append('\n');
    sb.append("- Predicted unknown: ").append(evaluation.predictionUnknownCount()).append('\n');
    sb.append("- Correct: ").append(evaluation.correctCount()).append('\n');
    sb.append("- Accuracy: ");
    if (evaluation.accuracy() == null) {
      sb.append("n/a\n");
    } else {
      sb.append(String.format(Locale.ROOT, "%.3f", evaluation.accuracy())).append('\n');
    }
    sb.append("- Evaluated accuracy (excl. unknown GT): ");
    if (evaluation.evaluatedAccuracy() == null) {
      sb.append("n/a\n\n");
    } else {
      sb.append(String.format(Locale.ROOT, "%.3f", evaluation.evaluatedAccuracy())).append("\n\n");
    }
    sb.append("## Per-Label Statistics\n\n");
    sb.append("| Ground truth | Samples | Correct | Recall | Precision |\n");
    sb.append("|---|---:|---:|---:|---:|\n");
    for (Map.Entry<String, Integer> entry : evaluation.labelSampleCounts().entrySet()) {
      String label = entry.getKey();
      Double recall = evaluation.recall(label);
      Double precision = evaluation.precision(label);
      sb.append("| ")
          .append(label)
          .append(" | ")
          .append(entry.getValue())
          .append(" | ")
          .append(evaluation.labelCorrectCounts().getOrDefault(label, 0))
          .append(" | ")
          .append(recall == null ? "n/a" : String.format(Locale.ROOT, "%.3f", recall))
          .append(" | ")
          .append(precision == null ? "n/a" : String.format(Locale.ROOT, "%.3f", precision))
          .append(" |\n");
    }
    sb.append("\n## Confusion Matrix\n\n");
    java.util.Set<String> allLabels = evaluation.allLabels();
    sb.append("| Actual \\ Predicted |");
    for (String predicted : allLabels) {
      sb.append(' ').append(predicted).append(" |");
    }
    sb.append('\n');
    sb.append("|---|");
    for (int i = 0; i < allLabels.size(); i++) {
      sb.append("---:|");
    }
    sb.append('\n');
    for (String actual : evaluation.labelSampleCounts().keySet()) {
      Map<String, Integer> row = evaluation.confusionMatrix().getOrDefault(actual, Map.of());
      sb.append("| ").append(actual).append(" |");
      for (String predicted : allLabels) {
        sb.append(' ').append(row.getOrDefault(predicted, 0)).append(" |");
      }
      sb.append('\n');
    }
    return sb.toString();
  }

  private static Path resolveAudioPath(DatasetManifest manifest, DatasetRecording recording) {
    Path audioPath = recording.audioPath();
    if (audioPath.isAbsolute()) {
      return audioPath;
    }
    return manifest.descriptor().localRootPath().resolve(audioPath).normalize();
  }

  private static String describe(DatasetRecording recording) {
    String species = recording.labels().get("species");
    String gender = recording.labels().get("gender");
    String soundType = recording.labels().get("sound_type");
    StringBuilder description = new StringBuilder(recording.recordingId());
    if (species != null && !species.isBlank()) {
      description.append(" / species=").append(species);
    }
    if (gender != null && !gender.isBlank()) {
      description.append(" / gender=").append(gender);
    }
    if (soundType != null && !soundType.isBlank()) {
      description.append(" / sound_type=").append(soundType);
    }
    return description.toString();
  }

  private static String defaultGroundTruthLabel(DatasetRecording recording) {
    String soundType = normalized(recording.labels().get("sound_type"));
    String gender = normalized(recording.labels().get("gender"));
    String fed = normalized(recording.labels().get("fed"));
    if ("female".equals(gender)) {
      if (isTruthy(fed)) {
        return WingbeatLabel.POSSIBLY_BLOOD_FED_FEMALE;
      }
      return WingbeatLabel.FEMALE_LIKELY;
    }
    if ("male".equals(gender)) {
      return WingbeatLabel.MALE_LIKELY;
    }
    if (soundType.contains("background") || soundType.contains("noise")) {
      return WingbeatLabel.UNKNOWN;
    }
    if (soundType.contains("mosquito") || recording.labels().containsKey("species")) {
      return WingbeatLabel.MOSQUITO_LIKE;
    }
    return WingbeatLabel.UNKNOWN;
  }

  private static boolean isTruthy(String value) {
    return "1".equals(value)
        || "true".equals(value)
        || "yes".equals(value)
        || "y".equals(value)
        || "fed".equals(value)
        || "blood-fed".equals(value);
  }

  private static String normalized(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  @FunctionalInterface
  interface GroundTruthLabelResolver {
    String resolve(DatasetRecording recording);
  }

  /**
   * Result of analyzing one imported recording.
   *
   * @param recording imported recording metadata entry
   * @param resolvedAudioPath resolved absolute audio path
   * @param audioBlock decoded audio block used for analysis
   * @param groundTruthLabel derived classifier ground-truth label
   * @param features extracted wingbeat feature vector
   * @param classificationResult classifier output, or {@code null} when no classifier was supplied
   */
  public record RecordingAnalysis(
      DatasetRecording recording,
      Path resolvedAudioPath,
      AudioBlock audioBlock,
      String groundTruthLabel,
      WingbeatFeatureVector features,
      ClassificationResult classificationResult) {

    public RecordingAnalysis {
      Objects.requireNonNull(recording, "recording");
      Objects.requireNonNull(resolvedAudioPath, "resolvedAudioPath");
      Objects.requireNonNull(audioBlock, "audioBlock");
      Objects.requireNonNull(groundTruthLabel, "groundTruthLabel");
      Objects.requireNonNull(features, "features");
    }
  }
}
