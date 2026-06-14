package org.hammer.audio.experimental.acoustic.benchmark;

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
    double trackContinuity,
    double idStability,
    double sourceCountAccuracy,
    double meanSourceCountError,
    double falsePositiveRate,
    double falseNegativeRate,
    long meanProcessingNanos,
    long medianProcessingNanos,
    long maxProcessingNanos) {

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
    validateRate(trackContinuity, "trackContinuity");
    validateRate(idStability, "idStability");
    validateRate(sourceCountAccuracy, "sourceCountAccuracy");
    validateNonNegative(meanSourceCountError, "meanSourceCountError");
    validateRate(falsePositiveRate, "falsePositiveRate");
    validateRate(falseNegativeRate, "falseNegativeRate");
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
        "meanAbsoluteFrequencyErrorHz",
        "medianAbsoluteFrequencyErrorHz",
        "meanRelativeFrequencyError",
        "meanAbsoluteDopplerErrorMps",
        "medianAbsoluteDopplerErrorMps",
        "classificationAccuracy",
        "trackContinuity",
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
        format(frequency.meanAbsoluteErrorHz()),
        format(frequency.medianAbsoluteErrorHz()),
        format(frequency.meanRelativeError()),
        format(doppler.meanAbsoluteErrorMetersPerSecond()),
        format(doppler.medianAbsoluteErrorMetersPerSecond()),
        format(classification.accuracy()),
        format(trackContinuity),
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
    return "{"
        + jsonField("scenarioId", jsonEscape(scenarioId))
        + ",\"expectedSourceCount\":"
        + expectedSourceCount
        + ",\"snapshotCount\":"
        + snapshotCount
        + ",\"localization\":{\"meanDistanceErrorMeters\":"
        + format(localization.meanDistanceErrorMeters())
        + ",\"medianDistanceErrorMeters\":"
        + format(localization.medianDistanceErrorMeters())
        + ",\"meanAngularErrorDegrees\":"
        + format(localization.meanAngularErrorDegrees())
        + ",\"medianAngularErrorDegrees\":"
        + format(localization.medianAngularErrorDegrees())
        + ",\"sampleCount\":"
        + localization.sampleCount()
        + "}"
        + ",\"frequency\":{\"meanAbsoluteErrorHz\":"
        + format(frequency.meanAbsoluteErrorHz())
        + ",\"medianAbsoluteErrorHz\":"
        + format(frequency.medianAbsoluteErrorHz())
        + ",\"meanRelativeError\":"
        + format(frequency.meanRelativeError())
        + ",\"sampleCount\":"
        + frequency.sampleCount()
        + "}"
        + ",\"doppler\":{\"meanAbsoluteErrorMetersPerSecond\":"
        + format(doppler.meanAbsoluteErrorMetersPerSecond())
        + ",\"medianAbsoluteErrorMetersPerSecond\":"
        + format(doppler.medianAbsoluteErrorMetersPerSecond())
        + ",\"sampleCount\":"
        + doppler.sampleCount()
        + "}"
        + ",\"classification\":{\"accuracy\":"
        + format(classification.accuracy())
        + ",\"correctCount\":"
        + classification.correctCount()
        + ",\"comparedCount\":"
        + classification.comparedCount()
        + "}"
        + ",\"trackContinuity\":"
        + format(trackContinuity)
        + ",\"idStability\":"
        + format(idStability)
        + ",\"sourceCountAccuracy\":"
        + format(sourceCountAccuracy)
        + ",\"meanSourceCountError\":"
        + format(meanSourceCountError)
        + ",\"falsePositiveRate\":"
        + format(falsePositiveRate)
        + ",\"falseNegativeRate\":"
        + format(falseNegativeRate)
        + ",\"meanProcessingNanos\":"
        + meanProcessingNanos
        + ",\"medianProcessingNanos\":"
        + medianProcessingNanos
        + ",\"maxProcessingNanos\":"
        + maxProcessingNanos
        + "}";
  }

  /** Render a human-friendly markdown summary suitable for benchmark reports. */
  public String toMarkdownSummary() {
    return "| Scenario | Median position error (m) | Mean frequency error (Hz) | Track continuity |"
        + " ID stability | False+ | False- | Mean processing (ns) |\n"
        + "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n"
        + "| "
        + scenarioId
        + " | "
        + format(localization.medianDistanceErrorMeters())
        + " | "
        + format(frequency.meanAbsoluteErrorHz())
        + " | "
        + format(trackContinuity)
        + " | "
        + format(idStability)
        + " | "
        + format(falsePositiveRate)
        + " | "
        + format(falseNegativeRate)
        + " | "
        + meanProcessingNanos
        + " |";
  }

  private static String jsonField(String name, String value) {
    return "\"" + name + "\":\"" + value + "\"";
  }

  private static String csvEscape(String value) {
    return '"' + value.replace("\"", "\"\"") + '"';
  }

  private static String jsonEscape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String format(double value) {
    return String.format(Locale.ROOT, "%.6f", value);
  }

  private static void validateRate(double value, String fieldName) {
    if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
      throw new IllegalArgumentException(fieldName + " must be finite and in [0,1]");
    }
  }

  private static void validateNonNegative(double value, String fieldName) {
    if (!Double.isFinite(value) || value < 0.0) {
      throw new IllegalArgumentException(fieldName + " must be finite and >= 0");
    }
  }
}
