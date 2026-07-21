package org.hammer.audio.experimental.acoustic;

/**
 * Confidence evidence for one GCC-PHAT lag estimate.
 *
 * @param interpolatedLagSamples estimated lag at sub-sample resolution
 * @param primaryPeak absolute score of the selected correlation peak
 * @param secondaryPeak strongest score outside the selected peak's one-sample guard region
 * @param peakRatio primary-to-secondary peak ratio
 * @param normalizedCurvature interpolation-resolution-independent local peak curvature
 * @param ambiguous whether the configured separation or curvature threshold was missed
 */
public record TdoaPeakDiagnostics(
    double interpolatedLagSamples,
    double primaryPeak,
    double secondaryPeak,
    double peakRatio,
    double normalizedCurvature,
    boolean ambiguous) {

  // Validate one immutable peak diagnostic.
  public TdoaPeakDiagnostics {
    requireFinite(interpolatedLagSamples, "interpolatedLagSamples");
    requireNonNegativeFinite(primaryPeak, "primaryPeak");
    requireNonNegativeFinite(secondaryPeak, "secondaryPeak");
    requireNonNegativeFinite(peakRatio, "peakRatio");
    requireNonNegativeFinite(normalizedCurvature, "normalizedCurvature");
    if (secondaryPeak > primaryPeak) {
      throw new IllegalArgumentException("secondaryPeak must not exceed primaryPeak");
    }
  }

  /** Normalized separation of the primary peak from competing peaks. */
  public double separation() {
    return primaryPeak > 0.0 ? Math.max(0.0, 1.0 - secondaryPeak / primaryPeak) : 0.0;
  }

  private static void requireFinite(double value, String name) {
    if (Double.isFinite(value)) {
      return;
    }
    throw new IllegalArgumentException(name + " must be finite");
  }

  private static void requireNonNegativeFinite(double value, String name) {
    if (Double.isFinite(value) && value >= 0.0) {
      return;
    }
    throw new IllegalArgumentException(name + " must be finite and >= 0");
  }
}
