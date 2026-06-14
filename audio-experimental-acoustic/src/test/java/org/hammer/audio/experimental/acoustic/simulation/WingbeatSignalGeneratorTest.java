package org.hammer.audio.experimental.acoustic.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.hammer.audio.experimental.acoustic.FrequencyBand;
import org.hammer.audio.experimental.acoustic.SpectralPeak;
import org.hammer.audio.experimental.acoustic.WingbeatFrequencyTracker;
import org.hammer.audio.experimental.acoustic.scenario.AcousticGroundTruth;
import org.hammer.audio.experimental.acoustic.scenario.Scenario;
import org.junit.jupiter.api.Test;

class WingbeatSignalGeneratorTest {

  private static final AudioFormatDescriptor MONO = new AudioFormatDescriptor(16_000.0f, 1, 32);
  private static final int FFT_SIZE = 2048;

  // ---- Construction ----

  @Test
  void rejectsNullFormat() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new WingbeatSignalGenerator(null, WingbeatSignalParameters.of(440.0), 0L));
  }

  @Test
  void rejectsNullParams() {
    assertThrows(IllegalArgumentException.class, () -> new WingbeatSignalGenerator(MONO, null, 0L));
  }

  @Test
  void rejectsFramesLessThanOne() {
    WingbeatSignalGenerator gen =
        new WingbeatSignalGenerator(MONO, WingbeatSignalParameters.of(440.0), 0L);
    assertThrows(IllegalArgumentException.class, () -> gen.nextBlock(0));
  }

  // ---- Determinism ----

  @Test
  void generatorIsDeterministicWithSameSeed() {
    WingbeatSignalParameters params = WingbeatSignalParameters.mosquitoLike(600.0);
    WingbeatSignalGenerator g1 = new WingbeatSignalGenerator(MONO, params, 42L);
    WingbeatSignalGenerator g2 = new WingbeatSignalGenerator(MONO, params, 42L);

    AudioBlock b1 = g1.nextBlock(FFT_SIZE);
    AudioBlock b2 = g2.nextBlock(FFT_SIZE);

    assertTrue(
        Arrays.equals(b1.channelView(0), b2.channelView(0)),
        "identical seed must produce identical samples");
  }

  @Test
  void resetRestoresInitialState() {
    WingbeatSignalParameters params = WingbeatSignalParameters.mosquitoLike(600.0);
    WingbeatSignalGenerator gen = new WingbeatSignalGenerator(MONO, params, 7L);

    AudioBlock first = gen.nextBlock(FFT_SIZE);
    gen.reset();
    AudioBlock second = gen.nextBlock(FFT_SIZE);

    assertTrue(
        Arrays.equals(first.channelView(0), second.channelView(0)),
        "reset must restore bit-identical output");
    assertEquals(0L, second.frameIndex(), "frame index must restart at 0 after reset");
  }

  @Test
  void differentSeedsProduceDifferentJitterSequences() {
    WingbeatSignalParameters withJitter =
        new WingbeatSignalParameters(600.0, 1, null, 0.0, 0.0, 0.0, 5.0, 0.0);
    WingbeatSignalGenerator g1 = new WingbeatSignalGenerator(MONO, withJitter, 1L);
    WingbeatSignalGenerator g2 = new WingbeatSignalGenerator(MONO, withJitter, 2L);

    AudioBlock b1 = g1.nextBlock(256);
    AudioBlock b2 = g2.nextBlock(256);

    assertFalse(
        Arrays.equals(b1.channelView(0), b2.channelView(0)),
        "different seeds must produce different jitter sequences");
  }

  // ---- Frequency recovery ----

  @Test
  void fundamentalFrequencyIsRecoverableFromGeneratedAudio() {
    double targetHz = 600.0;
    WingbeatSignalParameters params = WingbeatSignalParameters.of(targetHz);
    WingbeatSignalGenerator gen = new WingbeatSignalGenerator(MONO, params, 1L);
    AudioBlock block = gen.nextBlock(FFT_SIZE);

    WingbeatFrequencyTracker tracker =
        new WingbeatFrequencyTracker(FFT_SIZE, new FrequencyBand(400.0, 800.0));
    SpectralPeak peak = tracker.track(block, 0);

    double binWidthHz = (double) MONO.sampleRate() / FFT_SIZE;
    assertEquals(
        targetHz,
        peak.frequencyHz(),
        binWidthHz,
        "recovered frequency must be within one FFT bin of the true fundamental");
    assertTrue(peak.magnitude() > 0.0, "magnitude must be positive");
  }

  @Test
  void fundamentalIsStillRecoverableWithMosquitoLikeParams() {
    double targetHz = 640.0;
    WingbeatSignalParameters params = WingbeatSignalParameters.mosquitoLike(targetHz);
    WingbeatSignalGenerator gen = new WingbeatSignalGenerator(MONO, params, 3L);
    AudioBlock block = gen.nextBlock(FFT_SIZE);

    WingbeatFrequencyTracker tracker =
        new WingbeatFrequencyTracker(FFT_SIZE, new FrequencyBand(400.0, 900.0));
    SpectralPeak peak = tracker.track(block, 0);

    double binWidthHz = (double) MONO.sampleRate() / FFT_SIZE;
    assertEquals(
        targetHz,
        peak.frequencyHz(),
        binWidthHz,
        "fundamental must be recoverable even with harmonics and noise");
  }

  // ---- Signal model ----

  @Test
  void amplitudeModulationAffectsOutput() {
    double freq = 500.0;
    WingbeatSignalParameters withAm =
        new WingbeatSignalParameters(freq, 1, null, 10.0, 0.5, 0.0, 0.0, 0.0);
    WingbeatSignalParameters withoutAm = WingbeatSignalParameters.of(freq);

    WingbeatSignalGenerator gAm = new WingbeatSignalGenerator(MONO, withAm, 0L);
    WingbeatSignalGenerator gNoAm = new WingbeatSignalGenerator(MONO, withoutAm, 0L);

    AudioBlock bAm = gAm.nextBlock(512);
    AudioBlock bNoAm = gNoAm.nextBlock(512);

    assertFalse(
        Arrays.equals(bAm.channelView(0), bNoAm.channelView(0)),
        "AM must produce different waveform than no AM");
  }

  @Test
  void samplesAreBoundedInNormalizedRange() {
    WingbeatSignalParameters params =
        new WingbeatSignalParameters(
            600.0, 4, List.of(1.0, 0.5, 0.25, 0.125), 5.0, 0.8, 0.0, 0.0, 0.5);
    WingbeatSignalGenerator gen = new WingbeatSignalGenerator(MONO, params, 0L);
    AudioBlock block = gen.nextBlock(4096);

    for (float s : block.channelView(0)) {
      assertTrue(s >= -1.0f && s <= 1.0f, "sample " + s + " is out of [-1, 1]");
    }
  }

  @Test
  void broadcastToAllChannels() {
    AudioFormatDescriptor stereo = new AudioFormatDescriptor(16_000.0f, 2, 32);
    WingbeatSignalGenerator gen =
        new WingbeatSignalGenerator(stereo, WingbeatSignalParameters.of(440.0), 0L);
    AudioBlock block = gen.nextBlock(128);

    assertEquals(2, block.channels());
    assertTrue(
        Arrays.equals(block.channelView(0), block.channelView(1)),
        "signal must be broadcast identically to all channels");
  }

  // ---- WingbeatSignalParameters ----

  @Test
  void parametersValidationRejectsInvalidInputs() {
    assertThrows(IllegalArgumentException.class, () -> WingbeatSignalParameters.of(0.0));
    assertThrows(IllegalArgumentException.class, () -> WingbeatSignalParameters.of(-100.0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WingbeatSignalParameters(440.0, 0, null, 0.0, 0.0, 0.0, 0.0, 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WingbeatSignalParameters(440.0, 2, List.of(1.0), 0.0, 0.0, 0.0, 0.0, 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WingbeatSignalParameters(440.0, 1, null, -1.0, 0.0, 0.0, 0.0, 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WingbeatSignalParameters(440.0, 1, null, 0.0, 1.5, 0.0, 0.0, 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WingbeatSignalParameters(440.0, 1, null, 0.0, 0.0, 0.0, -1.0, 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WingbeatSignalParameters(440.0, 1, null, 0.0, 0.0, 0.0, 0.0, -0.1));
  }

  @Test
  void parametersToGroundTruthExposesFrequencyAndHarmonics() {
    WingbeatSignalParameters params = WingbeatSignalParameters.mosquitoLike(600.0);
    AcousticGroundTruth truth = params.toGroundTruth();

    assertEquals(600.0, truth.fundamentalFrequencyHz());
    assertNotNull(truth.harmonics(), "harmonic amplitudes must be present");
    assertEquals(4, truth.harmonics().size());
    assertNotNull(truth.jitter(), "jitter must be exposed");
    assertNotNull(truth.drift(), "drift must be exposed");
  }

  @Test
  void ofFactoryProducesMinimalParameters() {
    WingbeatSignalParameters params = WingbeatSignalParameters.of(500.0);
    AcousticGroundTruth truth = params.toGroundTruth();

    assertEquals(500.0, truth.fundamentalFrequencyHz());
    assertEquals(1, params.harmonicCount());
    assertEquals(0.0, params.modulationHz());
    assertEquals(0.0, params.jitterHz());
    assertEquals(0.0, params.driftHzPerSecond());
  }

  // ---- Scenario integration ----

  @Test
  void twoMosquitoWingbeatsScenarioHasTwoSources() {
    SimulationScenarios.SimulationScenario scenario = SimulationScenarios.twoMosquitoWingbeats();

    assertEquals(2, scenario.emitters().size());
    assertEquals(600.0, scenario.emitters().get(0).frequencyHz());
    assertEquals(640.0, scenario.emitters().get(1).frequencyHz());
  }

  @Test
  void twoMosquitoWingbeatsGroundTruthHasRichAcousticMetadata() {
    Scenario truth = SimulationScenarios.twoMosquitoWingbeatsGroundTruth();

    assertEquals(2, truth.sources().size());

    // Both sources have harmonic info and classification metadata.
    truth
        .sources()
        .forEach(
            source -> {
              AcousticGroundTruth acoustic = source.acousticProperties();
              assertNotNull(acoustic, "acoustic ground truth must be present");
              assertNotNull(acoustic.harmonics(), "harmonics must be present");
              assertFalse(acoustic.harmonics().isEmpty(), "harmonics list must not be empty");
              assertNotNull(acoustic.jitter(), "jitter must be exposed in ground truth");
              assertNotNull(acoustic.drift(), "drift must be exposed in ground truth");

              assertNotNull(source.labels(), "classification labels must be present");
              assertEquals("synthetic-mosquito-like", source.labels().species());
              assertEquals("mosquito", source.sourceType());
            });
  }

  @Test
  void twoMosquitoWingbeatsGroundTruthFrequenciesMatchEmitters() {
    Scenario truth = SimulationScenarios.twoMosquitoWingbeatsGroundTruth();

    assertEquals(600.0, truth.sources().get(0).acousticProperties().fundamentalFrequencyHz());
    assertEquals(640.0, truth.sources().get(1).acousticProperties().fundamentalFrequencyHz());
  }
}
