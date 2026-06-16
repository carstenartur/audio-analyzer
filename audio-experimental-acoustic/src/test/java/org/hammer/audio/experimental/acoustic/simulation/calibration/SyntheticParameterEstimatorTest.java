package org.hammer.audio.experimental.acoustic.simulation.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.hammer.audio.experimental.acoustic.simulation.WingbeatSignalParameters;
import org.hammer.audio.experimental.acoustic.wingbeat.WingbeatFeatureVector;
import org.junit.jupiter.api.Test;

class SyntheticParameterEstimatorTest {

  private static final double DELTA = 1e-9;
  private static final double LOOSE_DELTA = 1e-6;

  private final SyntheticParameterEstimator estimator = new SyntheticParameterEstimator();

  private static WingbeatFeatureVector fv(
      double freqHz, double jitter, double modulation, double snr, double drift) {
    return new WingbeatFeatureVector(
        freqHz, List.of(), List.of(), freqHz, 0.0, drift, jitter, modulation, snr, 1.0, 0.9);
  }

  private static WingbeatFeatureVector fvWithHarmonics(
      double freqHz, List<Double> harmonics, double snr) {
    List<Double> ratios =
        harmonics.size() >= 2 && harmonics.get(0) > 0.0
            ? harmonics.subList(1, harmonics.size()).stream()
                .map(a -> a / harmonics.get(0))
                .toList()
            : List.of();
    return new WingbeatFeatureVector(
        freqHz, harmonics, ratios, freqHz, 0.0, 0.0, 0.0, 0.0, snr, 1.0, 0.9);
  }

  @Test
  void estimateThrowsOnNullReal() {
    assertThrows(NullPointerException.class, () -> estimator.estimate(null));
  }

  @Test
  void estimateThrowsOnEmptyReal() {
    assertThrows(IllegalArgumentException.class, () -> estimator.estimate(List.of()));
  }

  @Test
  void estimateFundamentalFrequencyFromMean() {
    List<WingbeatFeatureVector> real =
        List.of(fv(400.0, 0.0, 0.0, 0.0, 0.0), fv(600.0, 0.0, 0.0, 0.0, 0.0));
    WingbeatSignalParameters params = estimator.estimate(real);
    assertEquals(500.0, params.fundamentalFrequencyHz(), DELTA);
  }

  @Test
  void estimateJitterFromMean() {
    List<WingbeatFeatureVector> real =
        List.of(fv(500.0, 3.0, 0.0, 0.0, 0.0), fv(500.0, 5.0, 0.0, 0.0, 0.0));
    WingbeatSignalParameters params = estimator.estimate(real);
    assertEquals(4.0, params.jitterHz(), DELTA);
  }

  @Test
  void estimateModulationDepthClamped() {
    // modulation = 0.9 → should be clamped to [0,1]
    List<WingbeatFeatureVector> real = List.of(fv(500.0, 0.0, 0.9, 0.0, 0.0));
    WingbeatSignalParameters params = estimator.estimate(real);
    assertEquals(0.9, params.modulationDepth(), LOOSE_DELTA);
  }

  @Test
  void estimateNoiseAmplitudeFromSnr() {
    // SNR = 9 → noiseAmplitude = 1/(9+1) = 0.1
    List<WingbeatFeatureVector> real = List.of(fv(500.0, 0.0, 0.0, 9.0, 0.0));
    WingbeatSignalParameters params = estimator.estimate(real);
    assertEquals(0.1, params.noiseAmplitude(), LOOSE_DELTA);
  }

  @Test
  void estimateNoiseAmplitudeFallbackWhenSnrIsZero() {
    List<WingbeatFeatureVector> real = List.of(fv(500.0, 0.0, 0.0, 0.0, 0.0));
    WingbeatSignalParameters params = estimator.estimate(real);
    assertEquals(
        SyntheticParameterEstimator.DEFAULT_NOISE_AMPLITUDE, params.noiseAmplitude(), DELTA);
  }

  @Test
  void estimateDriftFromMean() {
    List<WingbeatFeatureVector> real =
        List.of(fv(500.0, 0.0, 0.0, 0.0, 2.0), fv(500.0, 0.0, 0.0, 0.0, 4.0));
    WingbeatSignalParameters params = estimator.estimate(real);
    assertEquals(3.0, params.driftHzPerSecond(), DELTA);
  }

  @Test
  void harmonicAmplitudesNullWhenNoHarmonicData() {
    List<WingbeatFeatureVector> real = List.of(fv(500.0, 1.0, 0.0, 5.0, 0.0));
    WingbeatSignalParameters params = estimator.estimate(real);
    // No harmonic data → null amplitudes, count = 1
    assertNull(params.harmonicAmplitudes());
    assertEquals(1, params.harmonicCount());
  }

  @Test
  void harmonicAmplitudesEstimatedAndNormalized() {
    // Provide two harmonics [2.0, 1.0] → normalized to [1.0, 0.5]
    List<WingbeatFeatureVector> real =
        List.of(
            fvWithHarmonics(500.0, List.of(2.0, 1.0), 10.0),
            fvWithHarmonics(500.0, List.of(2.0, 1.0), 10.0));
    WingbeatSignalParameters params = estimator.estimate(real);
    assertNotNull(params.harmonicAmplitudes());
    assertEquals(2, params.harmonicCount());
    assertEquals(1.0, params.harmonicAmplitudes().get(0), LOOSE_DELTA);
    assertEquals(0.5, params.harmonicAmplitudes().get(1), LOOSE_DELTA);
  }

  @Test
  void estimateNoiseAmplitudeStaticMethod() {
    assertEquals(
        SyntheticParameterEstimator.DEFAULT_NOISE_AMPLITUDE,
        SyntheticParameterEstimator.estimateNoiseAmplitude(0.0),
        DELTA);
    assertEquals(0.1, SyntheticParameterEstimator.estimateNoiseAmplitude(9.0), LOOSE_DELTA);
  }

  @Test
  void estimateWithSingleVectorReturnsValidParams() {
    List<WingbeatFeatureVector> real = List.of(fv(650.0, 2.0, 0.3, 8.0, 0.5));
    WingbeatSignalParameters params = estimator.estimate(real);
    assertNotNull(params);
    assertTrue(params.fundamentalFrequencyHz() > 0);
    assertTrue(params.modulationDepth() >= 0.0 && params.modulationDepth() <= 1.0);
    assertTrue(params.noiseAmplitude() >= 0.0 && params.noiseAmplitude() <= 1.0);
  }
}
