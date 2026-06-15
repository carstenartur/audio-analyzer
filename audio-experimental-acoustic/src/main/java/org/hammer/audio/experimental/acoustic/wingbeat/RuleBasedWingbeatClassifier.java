package org.hammer.audio.experimental.acoustic.wingbeat;

import java.util.Objects;

/**
 * Rule-based baseline wingbeat classifier.
 *
 * <p>Classification is based on frequency thresholds derived from published mosquito wingbeat
 * literature. The boundaries are approximate and species-dependent; this classifier is intended as
 * a reproducible, transparent baseline rather than a production-grade identifier.
 *
 * <h3>Classification logic</h3>
 *
 * <ol>
 *   <li>If the fundamental frequency falls outside the broadest mosquito-like range, the result is
 *       {@link WingbeatLabel#UNKNOWN}.
 *   <li>Within the mosquito-like range, the frequency is compared against empirical sub-ranges:
 *       <ul>
 *         <li>{@link WingbeatLabel#POSSIBLY_BLOOD_FED_FEMALE}: 300–430 Hz (experimental lowest
 *             female range, capped to low confidence)
 *         <li>{@link WingbeatLabel#FEMALE_LIKELY}: 430–550 Hz (typical female range)
 *         <li>{@link WingbeatLabel#MALE_LIKELY}: above the overlap boundary (≥550 Hz)
 *         <li>{@link WingbeatLabel#MOSQUITO_LIKE}: near the 550 Hz boundary; returned with reduced
 *             confidence because the male/female ranges overlap here
 *       </ul>
 * </ol>
 *
 * <h3>Assumptions and limitations</h3>
 *
 * <ul>
 *   <li>Frequency thresholds represent approximate ranges for <em>Anopheles gambiae</em> and
 *       similar species. Other mosquito species may have different ranges.
 *   <li>The {@link WingbeatLabel#POSSIBLY_BLOOD_FED_FEMALE} branch is intentionally conservative:
 *       it is an exploratory label with a hard confidence cap and must not be read as a confirmed
 *       feeding-state prediction.
 *   <li>Environmental factors such as temperature, humidity and fatigue affect wingbeat frequency
 *       but are not modelled.
 *   <li>The classifier uses only the fundamental frequency and the feature confidence. Future
 *       implementations may incorporate harmonic structure, SNR and temporal features.
 *   <li>Confidence values are heuristic scores and have not been calibrated against a validated
 *       dataset. Do not interpret them as probabilities without empirical calibration.
 * </ul>
 *
 * <h3>Expected accuracy</h3>
 *
 * Without a validated labelled dataset, accuracy cannot be stated quantitatively. Evaluate this
 * classifier using {@link WingbeatDataset} before operational use.
 */
public final class RuleBasedWingbeatClassifier implements WingbeatClassifier {

  /** Lowest frequency considered mosquito-like (Hz). */
  static final double MOSQUITO_BAND_LOW_HZ = 300.0;

  /** Highest frequency considered mosquito-like (Hz). */
  static final double MOSQUITO_BAND_HIGH_HZ = 800.0;

  /** Upper bound of the blood-fed-female sub-range (Hz). */
  static final double BLOOD_FED_FEMALE_HIGH_HZ = 430.0;

  /** Maximum confidence for the experimental lowest-frequency female-like label. */
  static final double MAX_EXPERIMENTAL_BLOOD_FED_CONFIDENCE = 0.35;

  /** Transition frequency between female-likely and male-likely (Hz). */
  static final double FEMALE_MALE_BOUNDARY_HZ = 550.0;

  /**
   * Minimum band score below which the boundary region returns {@link WingbeatLabel#MOSQUITO_LIKE}
   * instead of {@link WingbeatLabel#MALE_LIKELY}.
   */
  private static final double OVERLAP_THRESHOLD = 0.15;

  @Override
  public ClassificationResult classify(WingbeatFeatureVector features) {
    Objects.requireNonNull(features, "features");
    double f = features.fundamentalFrequencyHz();
    if (f < MOSQUITO_BAND_LOW_HZ || f > MOSQUITO_BAND_HIGH_HZ) {
      return new ClassificationResult(WingbeatLabel.UNKNOWN, outOfRangeConfidence(f), features);
    }
    return subClassify(features, f);
  }

  private static ClassificationResult subClassify(WingbeatFeatureVector features, double f) {
    double bandScore = bandScore(f, MOSQUITO_BAND_LOW_HZ, MOSQUITO_BAND_HIGH_HZ);
    double baseConfidence = Math.min(1.0, bandScore * features.featureConfidence());

    if (f < BLOOD_FED_FEMALE_HIGH_HZ) {
      double subScore = bandScore(f, MOSQUITO_BAND_LOW_HZ, BLOOD_FED_FEMALE_HIGH_HZ);
      return new ClassificationResult(
          WingbeatLabel.POSSIBLY_BLOOD_FED_FEMALE,
          Math.min(MAX_EXPERIMENTAL_BLOOD_FED_CONFIDENCE, Math.min(1.0, baseConfidence * subScore)),
          features);
    }
    if (f < FEMALE_MALE_BOUNDARY_HZ) {
      double subScore = bandScore(f, BLOOD_FED_FEMALE_HIGH_HZ, FEMALE_MALE_BOUNDARY_HZ);
      return new ClassificationResult(
          WingbeatLabel.FEMALE_LIKELY, Math.min(1.0, baseConfidence * subScore), features);
    }
    double maleScore = bandScore(f, FEMALE_MALE_BOUNDARY_HZ, MOSQUITO_BAND_HIGH_HZ);
    if (maleScore < OVERLAP_THRESHOLD) {
      return new ClassificationResult(
          WingbeatLabel.MOSQUITO_LIKE, Math.min(1.0, baseConfidence * maleScore), features);
    }
    return new ClassificationResult(
        WingbeatLabel.MALE_LIKELY,
        Math.min(1.0, baseConfidence * (maleScore - OVERLAP_THRESHOLD)),
        features);
  }

  /**
   * Compute a band-position score in {@code [0,1]}: {@code 0} at either edge, {@code 1} at the
   * midpoint.
   */
  private static double bandScore(double value, double low, double high) {
    if (low >= high) {
      return 1.0;
    }
    double mid = (low + high) / 2.0;
    double halfWidth = (high - low) / 2.0;
    return 1.0 - Math.abs(value - mid) / halfWidth;
  }

  /**
   * Confidence for an out-of-range frequency; approaches {@code 1} as the frequency moves further
   * from the mosquito band, and {@code 0} at the band boundaries.
   */
  private static double outOfRangeConfidence(double f) {
    double distanceLow = Math.max(0.0, MOSQUITO_BAND_LOW_HZ - f);
    double distanceHigh = Math.max(0.0, f - MOSQUITO_BAND_HIGH_HZ);
    double distance = distanceLow + distanceHigh;
    double scale = (MOSQUITO_BAND_HIGH_HZ - MOSQUITO_BAND_LOW_HZ) / 2.0;
    return Math.min(1.0, distance / scale);
  }
}
