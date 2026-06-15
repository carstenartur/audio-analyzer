package org.hammer.audio.experimental.acoustic.wingbeat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.hammer.audio.experimental.acoustic.FrequencyBand;
import org.hammer.audio.experimental.acoustic.simulation.WingbeatSignalGenerator;
import org.hammer.audio.experimental.acoustic.simulation.WingbeatSignalParameters;
import org.hammer.audio.experimental.acoustic.tracking.TrackedSource;
import org.hammer.audio.geometry.Vector2;
import org.hammer.audio.geometry.Vector3;
import org.junit.jupiter.api.Test;

class WingbeatFeatureExtractorTest {

  private static final int SAMPLE_RATE = 8_192;
  private static final int FRAMES = 2_048;
  private static final int HARMONIC_SAMPLE_RATE = 16_384;
  private static final int HARMONIC_FRAMES = 4_096;
  private static final FrequencyBand BAND = new FrequencyBand(300.0, 800.0);

  @Test
  void metadataExtractPopulatesBasicFields() {
    WingbeatFeatureExtractor extractor = new WingbeatFeatureExtractor(1_024, BAND);
    TrackedSource source = source(500.0, 0.9, 100.0);

    WingbeatFeatureVector vector = extractor.extract(source, 2.5);

    assertEquals(500.0, vector.fundamentalFrequencyHz());
    assertEquals(2.5, vector.trackDurationSeconds());
    assertEquals(0.9, vector.featureConfidence());
    assertTrue(vector.harmonicAmplitudes().isEmpty());
    assertTrue(vector.harmonicRatios().isEmpty());
    assertEquals(0.0, vector.frequencyDriftHzPerSecond());
    assertEquals(0.0, vector.amplitudeModulation());
    assertEquals(0.0, vector.signalToNoiseRatio());
  }

  @Test
  void metadataExtractDerivesJitterFromVariance() {
    WingbeatFeatureExtractor extractor = new WingbeatFeatureExtractor(1_024, BAND);
    double variance = 25.0;
    TrackedSource source = source(500.0, 0.8, variance);

    WingbeatFeatureVector vector = extractor.extract(source, 1.0);

    assertEquals(Math.sqrt(variance), vector.frequencyJitterHz(), 1e-9);
    assertEquals(Math.sqrt(variance), vector.spectralBandwidthHz(), 1e-9);
  }

  @Test
  void audioEnhancedExtractPopulatesHarmonicFields() {
    WingbeatFeatureExtractor extractor = new WingbeatFeatureExtractor(FRAMES, BAND);
    TrackedSource source = source(440.0, 0.9, 4.0);
    AudioBlock block = sinBlock(440.0);

    WingbeatFeatureVector vector = extractor.extract(source, block, 0, 1.5);

    assertFalse(vector.harmonicAmplitudes().isEmpty());
    assertEquals(0.0, vector.frequencyDriftHzPerSecond());
    assertTrue(vector.signalToNoiseRatio() > 1.0);
    assertEquals(1.5, vector.trackDurationSeconds());
  }

  @Test
  void audioEnhancedExtractComputesHarmonicRatiosWhenFundamentalPresent() {
    WingbeatFeatureExtractor extractor = new WingbeatFeatureExtractor(FRAMES, BAND, 3);
    TrackedSource source = source(440.0, 0.9, 0.0);
    AudioBlock block = sinBlock(440.0);

    WingbeatFeatureVector vector = extractor.extract(source, block, 0, 1.0);

    assertEquals(3, vector.harmonicAmplitudes().size());
    assertFalse(vector.harmonicRatios().isEmpty());
    assertTrue(vector.harmonicAmplitudes().get(0) > 0.0);
  }

  @Test
  void audioEnhancedExtractIsDeterministicForSameInput() {
    WingbeatFeatureExtractor extractor = new WingbeatFeatureExtractor(HARMONIC_FRAMES, BAND, 4);
    TrackedSource source = source(512.0, 0.9, 0.0);
    AudioBlock block =
        harmonicBlock(
            new WingbeatSignalParameters(
                512.0, 4, List.of(1.0, 0.5, 0.25, 0.125), 0.0, 0.0, 0.0, 0.0, 0.0),
            7L);

    WingbeatFeatureVector first = extractor.extract(source, block, 0, 1.0);
    WingbeatFeatureVector second = extractor.extract(source, block, 0, 1.0);

    assertEquals(first, second);
  }

  @Test
  void audioEnhancedExtractRecoversConfiguredHarmonics() {
    WingbeatFeatureExtractor extractor = new WingbeatFeatureExtractor(HARMONIC_FRAMES, BAND, 4);
    TrackedSource source = source(512.0, 0.9, 0.0);
    AudioBlock block =
        harmonicBlock(
            new WingbeatSignalParameters(
                512.0, 4, List.of(1.0, 0.5, 0.25, 0.125), 0.0, 0.0, 0.0, 0.0, 0.0),
            11L);

    WingbeatFeatureVector vector = extractor.extract(source, block, 0, 1.0);

    assertEquals(4, vector.harmonicAmplitudes().size());
    assertEquals(3, vector.harmonicRatios().size());
    assertTrue(vector.harmonicAmplitudes().get(0) > vector.harmonicAmplitudes().get(1));
    assertTrue(vector.harmonicAmplitudes().get(1) > vector.harmonicAmplitudes().get(2));
    assertTrue(vector.harmonicAmplitudes().get(2) > vector.harmonicAmplitudes().get(3));
    assertTrue(vector.harmonicRatios().get(0) > vector.harmonicRatios().get(1));
    assertTrue(vector.harmonicRatios().get(1) > vector.harmonicRatios().get(2));
    assertTrue(vector.harmonicRatios().get(0) >= 0.35 && vector.harmonicRatios().get(0) <= 0.5);
    assertTrue(vector.harmonicRatios().get(1) >= 0.15 && vector.harmonicRatios().get(1) <= 0.3);
    assertTrue(vector.harmonicRatios().get(2) >= 0.05 && vector.harmonicRatios().get(2) <= 0.18);
  }

  @Test
  void constructorRejectsFftSizeNotPowerOfTwo() {
    assertThrows(IllegalArgumentException.class, () -> new WingbeatFeatureExtractor(1_000, BAND));
  }

  @Test
  void constructorRejectsFftSizeTooSmall() {
    assertThrows(IllegalArgumentException.class, () -> new WingbeatFeatureExtractor(128, BAND));
  }

  @Test
  void constructorRejectsZeroHarmonicCount() {
    assertThrows(
        IllegalArgumentException.class, () -> new WingbeatFeatureExtractor(1_024, BAND, 0));
  }

  @Test
  void extractRejectsNegativeDuration() {
    WingbeatFeatureExtractor extractor = new WingbeatFeatureExtractor(1_024, BAND);
    TrackedSource source = source(500.0, 0.9, 0.0);
    assertThrows(IllegalArgumentException.class, () -> extractor.extract(source, -1.0));
  }

  @Test
  void extractWithAudioRejectsInvalidChannel() {
    WingbeatFeatureExtractor extractor = new WingbeatFeatureExtractor(1_024, BAND);
    TrackedSource source = source(440.0, 0.9, 0.0);
    AudioBlock block = sinBlock(440.0);
    assertThrows(IllegalArgumentException.class, () -> extractor.extract(source, block, 5, 0.0));
  }

  @Test
  void spectralCentroidFallsWithinBand() {
    WingbeatFeatureExtractor extractor = new WingbeatFeatureExtractor(FRAMES, BAND);
    TrackedSource source = source(440.0, 0.9, 0.0);
    AudioBlock block = sinBlock(440.0);

    WingbeatFeatureVector vector = extractor.extract(source, block, 0, 0.0);

    assertNotNull(vector);
    assertTrue(
        vector.spectralCentroidHz() >= BAND.lowHz(),
        "centroid should be >= band low: " + vector.spectralCentroidHz());
    assertTrue(
        vector.spectralCentroidHz() <= BAND.highHz(),
        "centroid should be <= band high: " + vector.spectralCentroidHz());
  }

  private static TrackedSource source(double frequencyHz, double confidence, double variance) {
    return new TrackedSource(
        1,
        frequencyHz,
        frequencyHz,
        Vector2.ZERO,
        Vector2.ZERO,
        Vector3.ZERO,
        0.0,
        variance,
        confidence,
        0L,
        5);
  }

  private static AudioBlock sinBlock(double frequencyHz) {
    float[][] samples = new float[1][FRAMES];
    for (int i = 0; i < FRAMES; i++) {
      samples[0][i] = (float) Math.sin(2.0 * Math.PI * frequencyHz * i / SAMPLE_RATE);
    }
    return new AudioBlock(new AudioFormatDescriptor(SAMPLE_RATE, 1, 32), samples, 0, 0);
  }

  private static AudioBlock harmonicBlock(WingbeatSignalParameters params, long seed) {
    WingbeatSignalGenerator generator =
        new WingbeatSignalGenerator(new AudioFormatDescriptor(HARMONIC_SAMPLE_RATE, 1, 32), params, seed);
    return generator.nextBlock(HARMONIC_FRAMES);
  }
}
