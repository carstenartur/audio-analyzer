package org.hammer.audio.experimental.acoustic.wingbeat;

import java.util.List;
import java.util.Objects;

/**
 * Feature representation of a wingbeat acoustic signature.
 *
 * <p>The feature vector captures spectral and temporal characteristics of a narrowband tonal
 * source. All numeric features use SI units (Hz for frequencies, seconds for duration). Fields that
 * cannot be computed — for example harmonic features when no audio block is available — are
 * represented by empty lists or zero values rather than {@code null} or {@code NaN}.
 *
 * <p>The feature set is intentionally generic and is not limited to mosquitoes. Classifiers using
 * this vector should document which fields they actually rely on.
 *
 * @param fundamentalFrequencyHz estimated fundamental wingbeat frequency in Hz; must be finite and
 *     >= 0
 * @param harmonicAmplitudes linear FFT amplitudes at the fundamental and successive harmonics; may
 *     be empty when audio data is unavailable; never {@code null}
 * @param harmonicRatios ratio of each harmonic amplitude to the fundamental amplitude; may be empty
 *     when {@code harmonicAmplitudes} has fewer than two elements; never {@code null}
 * @param spectralCentroidHz magnitude-weighted mean frequency within the analysis band in Hz; must
 *     be finite and >= 0
 * @param spectralBandwidthHz standard deviation of the spectral distribution around the centroid in
 *     Hz; must be finite and >= 0
 * @param frequencyDriftHzPerSecond estimated linear frequency drift rate in Hz/s; {@code 0} when
 *     insufficient history is available; must be finite
 * @param frequencyJitterHz standard deviation of observed instantaneous frequencies around the
 *     smoothed fundamental in Hz; must be finite and >= 0
 * @param amplitudeModulation normalized amplitude-modulation index in {@code [0,1]}; {@code 0} when
 *     insufficient history is available
 * @param signalToNoiseRatio ratio of peak magnitude to median band noise floor; {@code 0} when
 *     audio data is unavailable; must be finite and >= 0
 * @param trackDurationSeconds elapsed duration since the source was first tracked in seconds; must
 *     be finite and >= 0
 * @param featureConfidence overall confidence in the extracted features in {@code [0,1]}
 */
public record WingbeatFeatureVector(
    double fundamentalFrequencyHz,
    List<Double> harmonicAmplitudes,
    List<Double> harmonicRatios,
    double spectralCentroidHz,
    double spectralBandwidthHz,
    double frequencyDriftHzPerSecond,
    double frequencyJitterHz,
    double amplitudeModulation,
    double signalToNoiseRatio,
    double trackDurationSeconds,
    double featureConfidence) {

  /* Validate fields and defensively copy lists. */
  public WingbeatFeatureVector {
    Objects.requireNonNull(harmonicAmplitudes, "harmonicAmplitudes");
    Objects.requireNonNull(harmonicRatios, "harmonicRatios");
    validateSeries(harmonicAmplitudes, "harmonicAmplitudes");
    validateSeries(harmonicRatios, "harmonicRatios");
    if (harmonicAmplitudes.isEmpty() && !harmonicRatios.isEmpty()) {
      throw new IllegalArgumentException(
          "harmonicRatios must be empty when harmonicAmplitudes is empty");
    }
    if (!Double.isFinite(fundamentalFrequencyHz) || fundamentalFrequencyHz < 0.0) {
      throw new IllegalArgumentException("fundamentalFrequencyHz must be finite and >= 0");
    }
    if (!Double.isFinite(spectralCentroidHz) || spectralCentroidHz < 0.0) {
      throw new IllegalArgumentException("spectralCentroidHz must be finite and >= 0");
    }
    if (!Double.isFinite(spectralBandwidthHz) || spectralBandwidthHz < 0.0) {
      throw new IllegalArgumentException("spectralBandwidthHz must be finite and >= 0");
    }
    if (!Double.isFinite(frequencyDriftHzPerSecond)) {
      throw new IllegalArgumentException("frequencyDriftHzPerSecond must be finite");
    }
    if (!Double.isFinite(frequencyJitterHz) || frequencyJitterHz < 0.0) {
      throw new IllegalArgumentException("frequencyJitterHz must be finite and >= 0");
    }
    if (!Double.isFinite(amplitudeModulation)
        || amplitudeModulation < 0.0
        || amplitudeModulation > 1.0) {
      throw new IllegalArgumentException("amplitudeModulation must be finite and in [0,1]");
    }
    if (!Double.isFinite(signalToNoiseRatio) || signalToNoiseRatio < 0.0) {
      throw new IllegalArgumentException("signalToNoiseRatio must be finite and >= 0");
    }
    if (!Double.isFinite(trackDurationSeconds) || trackDurationSeconds < 0.0) {
      throw new IllegalArgumentException("trackDurationSeconds must be finite and >= 0");
    }
    if (!Double.isFinite(featureConfidence) || featureConfidence < 0.0 || featureConfidence > 1.0) {
      throw new IllegalArgumentException("featureConfidence must be finite and in [0,1]");
    }
    harmonicAmplitudes = List.copyOf(harmonicAmplitudes);
    harmonicRatios = List.copyOf(harmonicRatios);
  }

  private static void validateSeries(List<Double> values, String fieldName) {
    for (int i = 0; i < values.size(); i++) {
      Double value = values.get(i);
      if (value == null) {
        throw new IllegalArgumentException(fieldName + "[" + i + "] must not be null");
      }
      if (Double.isNaN(value)) {
        throw new IllegalArgumentException(fieldName + "[" + i + "] must not be NaN");
      }
      if (Double.isInfinite(value)) {
        throw new IllegalArgumentException(fieldName + "[" + i + "] must be finite");
      }
      if (value < 0.0) {
        throw new IllegalArgumentException(fieldName + "[" + i + "] must be >= 0");
      }
    }
  }
}
