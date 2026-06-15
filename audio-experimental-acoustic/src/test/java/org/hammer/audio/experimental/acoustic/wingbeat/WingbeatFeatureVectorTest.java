package org.hammer.audio.experimental.acoustic.wingbeat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class WingbeatFeatureVectorTest {

  @Test
  void constructorAcceptsValidFields() {
    WingbeatFeatureVector vector =
        new WingbeatFeatureVector(
            500.0, List.of(1.0, 0.5), List.of(0.5), 510.0, 30.0, 1.0, 5.0, 0.1, 10.0, 2.0, 0.9);

    assertEquals(500.0, vector.fundamentalFrequencyHz());
    assertEquals(List.of(1.0, 0.5), vector.harmonicAmplitudes());
    assertEquals(List.of(0.5), vector.harmonicRatios());
    assertEquals(510.0, vector.spectralCentroidHz());
    assertEquals(30.0, vector.spectralBandwidthHz());
    assertEquals(1.0, vector.frequencyDriftHzPerSecond());
    assertEquals(5.0, vector.frequencyJitterHz());
    assertEquals(0.1, vector.amplitudeModulation());
    assertEquals(10.0, vector.signalToNoiseRatio());
    assertEquals(2.0, vector.trackDurationSeconds());
    assertEquals(0.9, vector.featureConfidence());
  }

  @Test
  void constructorAcceptsEmptyHarmonicLists() {
    WingbeatFeatureVector vector =
        new WingbeatFeatureVector(
            300.0, List.of(), List.of(), 300.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0);

    assertEquals(List.of(), vector.harmonicAmplitudes());
    assertEquals(List.of(), vector.harmonicRatios());
  }

  @Test
  void constructorAcceptsNegativeFrequencyDrift() {
    WingbeatFeatureVector vector =
        new WingbeatFeatureVector(
            500.0, List.of(), List.of(), 500.0, 0.0, -2.5, 0.0, 0.0, 0.0, 0.0, 1.0);

    assertEquals(-2.5, vector.frequencyDriftHzPerSecond());
  }

  @Test
  void constructorRejectsNegativeFundamentalFrequency() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WingbeatFeatureVector(
                -1.0, List.of(), List.of(), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0));
  }

  @Test
  void constructorRejectsConfidenceAboveOne() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WingbeatFeatureVector(
                500.0, List.of(), List.of(), 500.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.1));
  }

  @Test
  void constructorRejectsNegativeAmplitudeModulation() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WingbeatFeatureVector(
                500.0, List.of(), List.of(), 500.0, 0.0, 0.0, 0.0, -0.1, 0.0, 0.0, 1.0));
  }

  @Test
  void constructorRejectsNullHarmonicAmplitudes() {
    assertThrows(
        NullPointerException.class,
        () ->
            new WingbeatFeatureVector(
                500.0, null, List.of(), 500.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0));
  }

  @Test
  void harmonicListsAreDefensivelyCopied() {
    List<Double> amplitudes = new java.util.ArrayList<>(List.of(1.0, 0.5));
    WingbeatFeatureVector vector =
        new WingbeatFeatureVector(
            500.0, amplitudes, List.of(0.5), 500.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0);
    amplitudes.add(0.25);

    assertEquals(2, vector.harmonicAmplitudes().size());
  }
}
