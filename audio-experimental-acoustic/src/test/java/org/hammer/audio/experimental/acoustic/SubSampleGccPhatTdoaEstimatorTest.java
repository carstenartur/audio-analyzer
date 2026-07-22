package org.hammer.audio.experimental.acoustic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.hammer.audio.acquisition.Microphone;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.analysis.Fft;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.hammer.audio.experimental.acoustic.benchmark.tdoa.NamedTdoaEstimator;
import org.hammer.audio.experimental.acoustic.benchmark.tdoa.TdoaAlgorithmBenchmarkReport;
import org.hammer.audio.experimental.acoustic.benchmark.tdoa.TdoaAlgorithmBenchmarkRunner;
import org.hammer.audio.experimental.acoustic.benchmark.tdoa.TdoaBenchmarkCase;
import org.hammer.audio.geometry.Vector2;
import org.junit.jupiter.api.Test;

class SubSampleGccPhatTdoaEstimatorTest {

  private static final int FRAMES = 2_048;
  private static final float SAMPLE_RATE = 48_000.0f;
  private static final double SPEED_OF_SOUND = 343.0;
  private static final double NOISE_AMPLITUDE = 0.25;
  private static final double REFLECTION_GAIN = 0.45;
  private static final double REFLECTION_DELAY_SAMPLES = 10.7;

  @Test
  void resolvesKnownFractionalDelayBelowOneSixteenthSample() {
    SubSampleGccPhatTdoaEstimator estimator = new SubSampleGccPhatTdoaEstimator(SPEED_OF_SOUND);
    AudioBlock block = fractionalDelayBlock(2.4, 42L);

    DiagnosticTdoaEstimate result = estimator.estimateDetailed(block, array(), 0, 1);
    double estimatedSamples = result.estimate().delaySeconds() * SAMPLE_RATE;

    assertEquals(2.4, estimatedSamples, 0.0625);
    assertTrue(result.diagnostics().peakRatio() > 1.5);
    assertTrue(result.diagnostics().normalizedCurvature() > 1.5);
    assertFalse(result.diagnostics().ambiguous());
  }

  @Test
  void sideBySideBenchmarkImprovesFractionalDelayAccuracy() {
    TdoaAlgorithmBenchmarkReport report =
        benchmark(
            List.of(
                benchmarkCase("positive-2.2", fractionalDelayBlock(2.2, 11L), 2.2),
                benchmarkCase("positive-2.5", fractionalDelayBlock(2.5, 12L), 2.5),
                benchmarkCase("positive-2.8", fractionalDelayBlock(2.8, 13L), 2.8),
                benchmarkCase("negative-2.4", fractionalDelayBlock(-2.4, 14L), -2.4)));

    assertAdvancedImproves(report, 0.08, 0.3);
    assertEquals("sub-sample-gcc-phat", report.mostAccurate().algorithmName());
    assertTrue(report.toMarkdown().contains("Mean abs. error (samples)"));
  }

  @Test
  void sideBySideBenchmarkImprovesNoisyFractionalDelayAccuracy() {
    TdoaAlgorithmBenchmarkReport report =
        benchmark(
            List.of(
                benchmarkCase("noisy-positive-2.2", noisyDelayBlock(2.2, 21L), 2.2),
                benchmarkCase("noisy-positive-2.5", noisyDelayBlock(2.5, 22L), 2.5),
                benchmarkCase("noisy-positive-2.8", noisyDelayBlock(2.8, 23L), 2.8),
                benchmarkCase("noisy-negative-2.4", noisyDelayBlock(-2.4, 24L), -2.4)));

    assertAdvancedImproves(report, 0.05, 0.25);
  }

  @Test
  void sideBySideBenchmarkImprovesReflectedFractionalDelayAccuracy() {
    TdoaAlgorithmBenchmarkReport report =
        benchmark(
            List.of(
                benchmarkCase("reflected-positive-2.2", reflectedDelayBlock(2.2, 31L), 2.2),
                benchmarkCase("reflected-positive-2.5", reflectedDelayBlock(2.5, 32L), 2.5),
                benchmarkCase("reflected-positive-2.8", reflectedDelayBlock(2.8, 33L), 2.8),
                benchmarkCase("reflected-negative-2.4", reflectedDelayBlock(-2.4, 34L), -2.4)));

    assertAdvancedImproves(report, 0.08, 0.4);
  }

  @Test
  void marksPeriodicMultiPeakSignalAmbiguousAndReliableApiRejectsIt() {
    SubSampleGccPhatTdoaEstimator estimator = new SubSampleGccPhatTdoaEstimator(SPEED_OF_SOUND);
    AudioBlock block = periodicDelayBlock(2.4);

    DiagnosticTdoaEstimate detailed = estimator.estimateDetailed(block, array(), 0, 1);

    assertTrue(detailed.diagnostics().ambiguous());
    assertTrue(detailed.diagnostics().normalizedCurvature() < 1.5);
    assertThrows(
        AmbiguousTdoaEstimateException.class,
        () -> estimator.estimateReliable(block, array(), 0, 1));
  }

  private static void assertAdvancedImproves(
      TdoaAlgorithmBenchmarkReport report, double maximumErrorSamples, double baselineFraction) {
    double integerError = report.results().get(0).meanAbsoluteErrorSamples();
    double subSampleError = report.results().get(1).meanAbsoluteErrorSamples();
    assertTrue(subSampleError < maximumErrorSamples, report.toMarkdown());
    assertTrue(subSampleError < integerError * baselineFraction, report.toMarkdown());
  }

  private static TdoaAlgorithmBenchmarkReport benchmark(List<TdoaBenchmarkCase> cases) {
    return new TdoaAlgorithmBenchmarkRunner(
            List.of(
                new NamedTdoaEstimator(
                    "integer-gcc-phat", new GccPhatTdoaEstimator(SPEED_OF_SOUND)),
                new NamedTdoaEstimator(
                    "sub-sample-gcc-phat", new SubSampleGccPhatTdoaEstimator(SPEED_OF_SOUND))))
        .run(cases);
  }

  private static TdoaBenchmarkCase benchmarkCase(
      String id, AudioBlock block, double expectedDelaySamples) {
    return new TdoaBenchmarkCase(id, block, array(), 0, 1, expectedDelaySamples);
  }

  private static AudioBlock fractionalDelayBlock(double delaySamples, long seed) {
    float[] source = broadbandSource(seed);
    return block(source, fractionalDelay(source, delaySamples));
  }

  private static AudioBlock noisyDelayBlock(double delaySamples, long seed) {
    float[] source = broadbandSource(seed);
    return block(
        addNoise(source, seed + 100L, NOISE_AMPLITUDE),
        addNoise(fractionalDelay(source, delaySamples), seed + 200L, NOISE_AMPLITUDE));
  }

  private static AudioBlock reflectedDelayBlock(double delaySamples, long seed) {
    float[] source = broadbandSource(seed);
    float[] first = mix(source, fractionalDelay(source, REFLECTION_DELAY_SAMPLES), REFLECTION_GAIN);
    float[] second =
        mix(
            fractionalDelay(source, delaySamples),
            fractionalDelay(source, delaySamples + REFLECTION_DELAY_SAMPLES + 1.7),
            REFLECTION_GAIN);
    return block(addNoise(first, seed + 100L, 0.02), addNoise(second, seed + 200L, 0.02));
  }

  private static float[] broadbandSource(long seed) {
    float[] source = new float[FRAMES];
    Random random = new Random(seed);
    for (int index = 256; index < FRAMES - 256; index++) {
      source[index] = (float) (random.nextDouble() * 2.0 - 1.0);
    }
    return source;
  }

  private static float[] addNoise(float[] source, long seed, double amplitude) {
    float[] noisy = source.clone();
    Random random = new Random(seed);
    for (int index = 0; index < noisy.length; index++) {
      noisy[index] += (float) ((random.nextDouble() * 2.0 - 1.0) * amplitude);
    }
    return noisy;
  }

  private static float[] mix(float[] direct, float[] reflection, double reflectionGain) {
    float[] mixed = new float[direct.length];
    for (int index = 0; index < mixed.length; index++) {
      mixed[index] = (float) (direct[index] + reflectionGain * reflection[index]);
    }
    return mixed;
  }

  private static AudioBlock periodicDelayBlock(double delaySamples) {
    float[] source = new float[FRAMES];
    for (int index = 0; index < FRAMES; index++) {
      source[index] = (float) Math.sin(2.0 * Math.PI * 4_000.0 * index / SAMPLE_RATE);
    }
    return block(source, fractionalDelay(source, delaySamples));
  }

  private static AudioBlock block(float[] first, float[] second) {
    return AudioBlock.wrap(
        new AudioFormatDescriptor(SAMPLE_RATE, 2, 16), new float[][] {first, second}, 0L, 0L);
  }

  private static float[] fractionalDelay(float[] source, double delaySamples) {
    float[] real = source.clone();
    float[] imaginary = new float[source.length];
    Fft fft = new Fft(source.length);
    fft.forward(real, imaginary);
    for (int bin = 0; bin < source.length; bin++) {
      double frequency =
          bin <= source.length / 2
              ? bin / (double) source.length
              : (bin - source.length) / (double) source.length;
      double phase = -2.0 * Math.PI * frequency * delaySamples;
      double cosine = Math.cos(phase);
      double sine = Math.sin(phase);
      double shiftedReal = real[bin] * cosine - imaginary[bin] * sine;
      double shiftedImaginary = real[bin] * sine + imaginary[bin] * cosine;
      real[bin] = (float) shiftedReal;
      imaginary[bin] = (float) shiftedImaginary;
    }
    inverse(fft, real, imaginary);
    return real;
  }

  private static void inverse(Fft fft, float[] real, float[] imaginary) {
    for (int index = 0; index < imaginary.length; index++) {
      imaginary[index] = -imaginary[index];
    }
    fft.forward(real, imaginary);
    for (int index = 0; index < real.length; index++) {
      real[index] /= real.length;
    }
  }

  private static MicrophoneArray array() {
    return new MicrophoneArray(
        List.of(
            new Microphone("left", new Vector2(-0.1, 0.0), 0),
            new Microphone("right", new Vector2(0.1, 0.0), 1)));
  }
}
