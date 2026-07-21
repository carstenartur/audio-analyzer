package org.hammer.audio.experimental.acoustic;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

  @Test
  void resolvesKnownFractionalDelayBelowOneSixteenthSample() {
    SubSampleGccPhatTdoaEstimator estimator =
        new SubSampleGccPhatTdoaEstimator(SPEED_OF_SOUND);
    AudioBlock block = fractionalDelayBlock(2.4, 42L);

    DiagnosticTdoaEstimate result = estimator.estimateDetailed(block, array(), 0, 1);
    double estimatedSamples = result.estimate().delaySeconds() * SAMPLE_RATE;

    assertEquals(2.4, estimatedSamples, 0.0625);
    assertTrue(result.diagnostics().peakRatio() > 1.5);
    assertTrue(result.diagnostics().normalizedCurvature() > 0.1);
    assertTrue(!result.diagnostics().ambiguous());
  }

  @Test
  void sideBySideBenchmarkImprovesFractionalDelayAccuracy() {
    List<TdoaBenchmarkCase> cases =
        List.of(
            benchmarkCase("positive-2.2", 2.2, 11L),
            benchmarkCase("positive-2.5", 2.5, 12L),
            benchmarkCase("positive-2.8", 2.8, 13L),
            benchmarkCase("negative-2.4", -2.4, 14L));
    TdoaAlgorithmBenchmarkReport report =
        new TdoaAlgorithmBenchmarkRunner(
                List.of(
                    new NamedTdoaEstimator(
                        "integer-gcc-phat", new GccPhatTdoaEstimator(SPEED_OF_SOUND)),
                    new NamedTdoaEstimator(
                        "sub-sample-gcc-phat",
                        new SubSampleGccPhatTdoaEstimator(SPEED_OF_SOUND))))
            .run(cases);

    double integerError = report.results().get(0).meanAbsoluteErrorSamples();
    double subSampleError = report.results().get(1).meanAbsoluteErrorSamples();
    assertTrue(subSampleError < 0.08, report.toMarkdown());
    assertTrue(subSampleError < integerError * 0.3, report.toMarkdown());
    assertEquals("sub-sample-gcc-phat", report.mostAccurate().algorithmName());
    assertTrue(report.toMarkdown().contains("Mean abs. error (samples)"));
  }

  @Test
  void marksPeriodicMultiPeakSignalAmbiguousAndReliableApiRejectsIt() {
    SubSampleGccPhatTdoaEstimator estimator =
        new SubSampleGccPhatTdoaEstimator(SPEED_OF_SOUND);
    AudioBlock block = periodicDelayBlock(2.4);

    DiagnosticTdoaEstimate detailed = estimator.estimateDetailed(block, array(), 0, 1);

    assertTrue(detailed.diagnostics().ambiguous());
    assertTrue(detailed.diagnostics().peakRatio() < 1.5);
    assertThrows(
        AmbiguousTdoaEstimateException.class,
        () -> estimator.estimateReliable(block, array(), 0, 1));
  }

  private static TdoaBenchmarkCase benchmarkCase(String id, double delaySamples, long seed) {
    return new TdoaBenchmarkCase(
        id, fractionalDelayBlock(delaySamples, seed), array(), 0, 1, delaySamples);
  }

  private static AudioBlock fractionalDelayBlock(double delaySamples, long seed) {
    float[] source = new float[FRAMES];
    Random random = new Random(seed);
    for (int index = 256; index < FRAMES - 256; index++) {
      source[index] = (float) (random.nextDouble() * 2.0 - 1.0);
    }
    return block(source, fractionalDelay(source, delaySamples));
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
        new AudioFormatDescriptor(SAMPLE_RATE, 2, 16),
        new float[][] {first, second},
        0L,
        0L);
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
