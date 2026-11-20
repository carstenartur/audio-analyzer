package org.hammer.audio.recording.runtime;

/**
 * Configurable byte and duration safety margins; all remaining times reserve criticalBytes.
 *
 * @param warningBytes usable-byte warning floor
 * @param criticalBytes usable-byte reserve for safe finalization
 * @param warningSeconds safe-duration warning floor
 * @param criticalSeconds safe-duration stopping floor
 * @param safetyMarginFraction additional planned-duration margin
 */
public record RecordingStoragePolicy(
    long warningBytes,
    long criticalBytes,
    long warningSeconds,
    long criticalSeconds,
    double safetyMarginFraction) {

  // Reject invalid policies instead of silently weakening a recording safety margin.
  public RecordingStoragePolicy {
    if (criticalBytes < 0L
        || warningBytes < criticalBytes
        || criticalSeconds < 0L
        || warningSeconds < criticalSeconds
        || !Double.isFinite(safetyMarginFraction)
        || safetyMarginFraction < 0.0) {
      throw new IllegalArgumentException(
          "Recording storage thresholds must be finite and ordered.");
    }
  }

  /** Load production thresholds from audio.recording.storage.* system properties. */
  public static RecordingStoragePolicy configured() {
    return new RecordingStoragePolicy(
        Long.getLong("audio.recording.storage.warningBytes", 256L * 1024L * 1024L),
        Long.getLong("audio.recording.storage.criticalBytes", 64L * 1024L * 1024L),
        Long.getLong("audio.recording.storage.warningSeconds", 300L),
        Long.getLong("audio.recording.storage.criticalSeconds", 30L),
        Double.parseDouble(System.getProperty("audio.recording.storage.safetyMargin", "0.1")));
  }

  /** Conservative capacity remaining after the finalization reserve. */
  public long safeSeconds(long usableBytes, double measuredRate, double expectedRate) {
    double rate = Math.max(measuredRate, expectedRate);
    if (usableBytes < 0L || rate <= 0.0 || !Double.isFinite(rate)) {
      return -1L;
    }
    return (long) Math.floor(Math.max(0L, usableBytes - criticalBytes) / rate);
  }

  /**
   * Evaluate both byte and time thresholds without mistaking unavailable capacity for low space.
   */
  public RecordingStorageLevel level(
      boolean writable, long usableBytes, double measuredRate, double expectedRate) {
    if (!writable) {
      return RecordingStorageLevel.CRITICAL;
    }
    if (usableBytes < 0L) {
      return RecordingStorageLevel.UNKNOWN;
    }
    long remaining = safeSeconds(usableBytes, measuredRate, expectedRate);
    if (usableBytes <= criticalBytes || (remaining >= 0L && remaining <= criticalSeconds)) {
      return RecordingStorageLevel.CRITICAL;
    }
    if (usableBytes <= warningBytes || (remaining >= 0L && remaining <= warningSeconds)) {
      return RecordingStorageLevel.WARNING;
    }
    return RecordingStorageLevel.NORMAL;
  }

  /** Whether the requested duration fits with the configured extra margin. */
  public boolean fits(long safeSeconds, long intendedSeconds) {
    return safeSeconds >= 0L && safeSeconds >= intendedSeconds * (1.0 + safetyMarginFraction);
  }
}
