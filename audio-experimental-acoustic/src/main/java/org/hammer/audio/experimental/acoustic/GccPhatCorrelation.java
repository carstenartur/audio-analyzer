package org.hammer.audio.experimental.acoustic;

import org.hammer.audio.analysis.Fft;

/** Package-local GCC-PHAT correlation engine with optional power-of-two interpolation. */
final class GccPhatCorrelation {

  private static final double EPSILON = 1.0e-12;

  private GccPhatCorrelation() {}

  /** Returns the PHAT-weighted circular correlation at the requested interpolation factor. */
  static double[] correlate(float[] first, float[] second, int frames, int interpolationFactor) {
    if (frames < 2 || frames > first.length || frames > second.length) {
      throw new IllegalArgumentException("frames must be in [2, min(channel lengths)]");
    }
    if (interpolationFactor < 1 || Integer.bitCount(interpolationFactor) != 1) {
      throw new IllegalArgumentException("interpolationFactor must be a positive power of two");
    }

    int fftSize = nextPowerOfTwo(frames * 2);
    float[] firstReal = new float[fftSize];
    float[] firstImaginary = new float[fftSize];
    float[] secondReal = new float[fftSize];
    float[] secondImaginary = new float[fftSize];
    System.arraycopy(first, 0, firstReal, 0, frames);
    System.arraycopy(second, 0, secondReal, 0, frames);

    Fft fft = new Fft(fftSize);
    fft.forward(firstReal, firstImaginary);
    fft.forward(secondReal, secondImaginary);

    float[] crossReal = new float[fftSize];
    float[] crossImaginary = new float[fftSize];
    for (int bin = 0; bin < fftSize; bin++) {
      double real = secondReal[bin] * firstReal[bin] + secondImaginary[bin] * firstImaginary[bin];
      double imaginary =
          secondImaginary[bin] * firstReal[bin] - secondReal[bin] * firstImaginary[bin];
      double magnitude = Math.hypot(real, imaginary);
      if (magnitude > EPSILON) {
        crossReal[bin] = (float) (real / magnitude);
        crossImaginary[bin] = (float) (imaginary / magnitude);
      }
    }

    if (interpolationFactor == 1) {
      inverse(fft, crossReal, crossImaginary);
      return asDoubleArray(crossReal, 1.0);
    }
    return interpolate(crossReal, crossImaginary, interpolationFactor);
  }

  private static double[] interpolate(
      float[] crossReal, float[] crossImaginary, int interpolationFactor) {
    int fftSize = crossReal.length;
    int interpolatedSize = Math.multiplyExact(fftSize, interpolationFactor);
    float[] interpolatedReal = new float[interpolatedSize];
    float[] interpolatedImaginary = new float[interpolatedSize];
    int half = fftSize / 2;
    System.arraycopy(crossReal, 0, interpolatedReal, 0, half + 1);
    System.arraycopy(crossImaginary, 0, interpolatedImaginary, 0, half + 1);
    int negativeFrequencyCount = fftSize - half - 1;
    System.arraycopy(
        crossReal,
        half + 1,
        interpolatedReal,
        interpolatedSize - negativeFrequencyCount,
        negativeFrequencyCount);
    System.arraycopy(
        crossImaginary,
        half + 1,
        interpolatedImaginary,
        interpolatedSize - negativeFrequencyCount,
        negativeFrequencyCount);

    inverse(new Fft(interpolatedSize), interpolatedReal, interpolatedImaginary);
    return asDoubleArray(interpolatedReal, interpolationFactor);
  }

  private static double[] asDoubleArray(float[] values, double scale) {
    double[] result = new double[values.length];
    for (int index = 0; index < values.length; index++) {
      result[index] = values[index] * scale;
    }
    return result;
  }

  private static void inverse(Fft fft, float[] real, float[] imaginary) {
    for (int index = 0; index < imaginary.length; index++) {
      imaginary[index] = -imaginary[index];
    }
    fft.forward(real, imaginary);
    for (int index = 0; index < real.length; index++) {
      real[index] /= real.length;
      imaginary[index] = -imaginary[index] / real.length;
    }
  }

  private static int nextPowerOfTwo(int value) {
    int result = 1;
    while (result < value) {
      result <<= 1;
    }
    return Math.max(2, result);
  }
}
