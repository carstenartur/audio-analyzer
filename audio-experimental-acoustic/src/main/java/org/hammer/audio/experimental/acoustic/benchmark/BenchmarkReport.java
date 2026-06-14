package org.hammer.audio.experimental.acoustic.benchmark;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Locale;
import java.util.Objects;

/** Machine-readable benchmark summary for one scenario execution. */
public record BenchmarkReport(
    String scenarioId,
    int expectedSourceCount,
    int snapshotCount,
    LocalizationErrorMetric localization,
    FrequencyErrorMetric frequency,
    DopplerErrorMetric doppler,
    ClassificationAccuracyMetric classification,
    Double trackContinuity,
    int trackContinuitySampleCount,
    int trackContinuityEvaluatedCount,
    int trackContinuitySkippedCount,
    int trackContinuityUnavailableTruthCount,
    Double idStability,
    Double sourceCountAccuracy,
    Double meanSourceCountError,
    Double falsePositiveRate,
    Double falseNegativeRate,
    long meanProcessingNanos,
    long medianProcessingNanos,
    long maxProcessingNanos) {

  private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

  public BenchmarkReport {
    Objects.requireNonNull(scenarioId, "scenarioId");
    Objects.requireNonNull(localization, "localization");
    Objects.requireNonNull(frequency, "frequency");
    Objects.requireNonNull(doppler, "doppler");
    Objects.requireNonNull(classification, "classification");
    if (scenarioId.isBlank()) {
      throw new IllegalArgumentException("scenarioId must not be blank");
    }
    if (expectedSourceCount < 0 || snapshotCount < 0) {
      throw new IllegalArgumentException("counts must be >= 0");
    }
    validateCounts(
        trackContinuitySampleCount,
        trackContinuityEvaluatedCount,
        trackContinuitySkippedCount,
        trackContinuityUnavailableTruthCount,
        "trackContinuity");
    validateNullableRate(trackContinuity, "trackContinuity");
    validateNullableRate(idStability, "idStability");
    validateNullableRate(sourceCountAccuracy, "sourceCountAccuracy");
    validateNullableNonNegative(meanSourceCountError, "meanSourceCountError");
    validateNullableRate(falsePositiveRate, "falsePositiveRate");
    validateNullableRate(falseNegativeRate, "falseNegativeRate");
    if (meanProcessingNanos < 0L || medianProcessingNanos < 0L || maxProcessingNanos < 0L) {
      throw new IllegalArgumentException("processing times must be >= 0");
    }
  }

  /** CSV header matching {@link #toCsvRow()}. */
  public static String csvHeader() {
    return String.join(
        ",",
        "scenarioId",
        "expectedSourceCount",
        "snapshotCount",
        "meanDistanceErrorMeters",
        "medianDistanceErrorMeters",
        "meanAngularErrorDegrees",
        "medianAngularErrorDegrees",
        "localizationSampleCount",
        "localizationEvaluatedCount",
        "localizationSkippedCount",
        "localizationUnavailableTruthCount",
        "meanAbsoluteFrequencyErrorHz",
        "medianAbsoluteFrequencyErrorHz",
        "meanRelativeFrequencyError",
        "frequencySampleCount",
        "frequencyEvaluatedCount",
        "frequencySkippedCount",
        "frequencyUnavailableTruthCount",
        "meanAbsoluteDopplerErrorMps",
        "medianAbsoluteDopplerErrorMps",
        "dopplerSampleCount",
        "dopplerEvaluatedCount",
        "dopplerSkippedCount",
        "dopplerUnavailableTruthCount",
        "classificationAccuracy",
        "classificationCorrectCount",
        "classificationSampleCount",
        "classificationEvaluatedCount",
        "classificationSkippedCount",
        "classificationUnavailableTruthCount",
        "trackContinuity",
        "trackContinuitySampleCount",
        "trackContinuityEvaluatedCount",
        "trackContinuitySkippedCount",
        "trackContinuityUnavailableTruthCount",
        "idStability",
        "sourceCountAccuracy",
        "meanSourceCountError",
        "falsePositiveRate",
        "falseNegativeRate",
        "meanProcessingNanos",
        "medianProcessingNanos",
        "maxProcessingNanos");
  }

  /** Serialize the report to one CSV row. */
  public String toCsvRow() {
    return String.join(
        ",",
        csvEscape(scenarioId),
        Integer.toString(expectedSourceCount),
        Integer.toString(snapshotCount),
        format(localization.meanDistanceErrorMeters()),
        format(localization.medianDistanceErrorMeters()),
        format(localization.meanAngularErrorDegrees()),
        format(localization.medianAngularErrorDegrees()),
        Integer.toString(localization.sampleCount()),
        Integer.toString(localization.evaluatedCount()),
        Integer.toString(localization.skippedCount()),
        Integer.toString(localization.unavailableTruthCount()),
        format(frequency.meanAbsoluteErrorHz()),
        format(frequency.medianAbsoluteErrorHz()),
        format(frequency.meanRelativeError()),
        Integer.toString(frequency.sampleCount()),
        Integer.toString(frequency.evaluatedCount()),
        Integer.toString(frequency.skippedCount()),
        Integer.toString(frequency.unavailableTruthCount()),
        format(doppler.meanAbsoluteErrorMetersPerSecond()),
        format(doppler.medianAbsoluteErrorMetersPerSecond()),
        Integer.toString(doppler.sampleCount()),
        Integer.toString(doppler.evaluatedCount()),
        Integer.toString(doppler.skippedCount()),
        Integer.toString(doppler.unavailableTruthCount()),
        format(classification.accuracy()),
        Integer.toString(classification.correctCount()),
        Integer.toString(classification.sampleCount()),
        Integer.toString(classification.evaluatedCount()),
        Integer.toString(classification.skippedCount()),
        Integer.toString(classification.unavailableTruthCount()),
        format(trackContinuity),
        Integer.toString(trackContinuitySampleCount),
        Integer.toString(trackContinuityEvaluatedCount),
        Integer.toString(trackContinuitySkippedCount),
        Integer.toString(trackContinuityUnavailableTruthCount),
        format(idStability),
        format(sourceCountAccuracy),
        format(meanSourceCountError),
        format(falsePositiveRate),
        format(falseNegativeRate),
        Long.toString(meanProcessingNanos),
        Long.toString(medianProcessingNanos),
        Long.toString(maxProcessingNanos));
  }

  /** Serialize the report to a compact JSON object. */
  public String toJson() {
    try {
      return OBJECT_MAPPER.writeValueAsString(this);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to serialize benchmark report", exception);
    }
  }

  /** Render a human-friendly markdown summary suitable for benchmark reports. */
  public String toMarkdownSummary() {
    return "| Scenario | Median position error (m) | Mean frequency error (Hz) | Track continuity |"
        + " ID stability | False+ | False- | Mean processing (ns) |\n"
        + "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n"
        + "| "
        + markdownEscape(scenarioId)
        + " | "
        + markdownFormat(localization.medianDistanceErrorMeters())
        + " | "
        + markdownFormat(frequency.meanAbsoluteErrorHz())
        + " | "
        + formatTrackContinuity()
        + " | "
        + markdownFormat(idStability)
        + " | "
        + markdownFormat(falsePositiveRate)
        + " | "
        + markdownFormat(falseNegativeRate)
        + " | "
        + meanProcessingNanos
        + " |";
  }

  private String formatTrackContinuity() {
    int continuityDenominator = trackContinuityEvaluatedCount + trackContinuitySkippedCount;
    if (trackContinuity == null) {
      return trackContinuityUnavailableTruthCount > 0
          ? "n/a (%d unavailable)".formatted(trackContinuityUnavailableTruthCount)
          : "n/a";
    }
    return "%s (%d/%d)"
        .formatted(
            format(trackContinuity),
            trackContinuityEvaluatedCount,
            Math.max(continuityDenominator, 1));
  }

  private static String csvEscape(String value) {
    return '"' + value.replace("\"", "\"\"") + '"';
  }

  private static String markdownEscape(String value) {
    return value.replace("\\", "\\\\").replace("|", "\\|").replace("\r", " ").replace("\n", " ");
  }

  private static String markdownFormat(Double value) {
    return value == null ? "n/a" : format(value);
  }

  private static String format(Double value) {
    if (value == null) {
      return "";
    }
    return String.format(Locale.ROOT, "%.6f", value);
  }

  private static void validateCounts(
      int sampleCount,
      int evaluatedCount,
      int skippedCount,
      int unavailableTruthCount,
      String fieldName) {
    if (sampleCount < 0 || evaluatedCount < 0 || skippedCount < 0 || unavailableTruthCount < 0) {
      throw new IllegalArgumentException(fieldName + " counts must be >= 0");
    }
    if (sampleCount != evaluatedCount + skippedCount + unavailableTruthCount) {
      throw new IllegalArgumentException(
          fieldName
              + " sampleCount must equal evaluatedCount + skippedCount + unavailableTruthCount");
    }
  }

  private static void validateNullableRate(Double value, String fieldName) {
    if (value != null && (!Double.isFinite(value) || value < 0.0 || value > 1.0)) {
      throw new IllegalArgumentException(fieldName + " must be finite and in [0,1]");
    }
  }

  private static void validateNullableNonNegative(Double value, String fieldName) {
    if (value != null && (!Double.isFinite(value) || value < 0.0)) {
      throw new IllegalArgumentException(fieldName + " must be finite and >= 0");
    }
  }
}
