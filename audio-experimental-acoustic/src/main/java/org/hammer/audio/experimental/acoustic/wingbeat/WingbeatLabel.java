package org.hammer.audio.experimental.acoustic.wingbeat;

/**
 * String constants for wingbeat classification labels.
 *
 * <p>Labels represent the output categories of a {@link WingbeatClassifier}. All labels are
 * lower-case dash-separated identifiers to enable consistent serialization and string comparison.
 *
 * <p>The label set is intentionally generic enough for narrowband acoustic emitters other than
 * mosquitoes; the set may be extended in future iterations without breaking callers that only check
 * for known constants.
 */
public final class WingbeatLabel {

  /** No confident classification could be assigned. */
  public static final String UNKNOWN = "unknown";

  /** Wingbeat frequency and harmonic structure are consistent with a mosquito-like source. */
  public static final String MOSQUITO_LIKE = "mosquito-like";

  /** Features suggest a male mosquito (higher fundamental frequency typical of males). */
  public static final String MALE_LIKELY = "male-likely";

  /** Features suggest a female mosquito (lower fundamental frequency typical of females). */
  public static final String FEMALE_LIKELY = "female-likely";

  /**
   * Features suggest a blood-fed female mosquito; the lowest wingbeat frequency sub-range,
   * consistent with the additional mass after a blood meal.
   */
  public static final String POSSIBLY_BLOOD_FED_FEMALE = "possibly-blood-fed-female";

  private WingbeatLabel() {}
}
