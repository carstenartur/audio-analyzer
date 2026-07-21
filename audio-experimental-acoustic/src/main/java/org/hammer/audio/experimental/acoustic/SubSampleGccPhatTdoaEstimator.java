package org.hammer.audio.experimental.acoustic;

import java.util.Objects;
import org.hammer.audio.acquisition.Microphone;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.core.AudioBlock;

/**
 * GCC-PHAT estimator using spectrally zero-padded correlation and explicit ambiguity diagnostics.
 */
public final class SubSampleGccPhatTdoaEstimator implements DiagnosticTdoaEstimator {

  private static final double EPSILON = 1.0e-12;
  private static final int DEFAULT_INTERPOLATION_FACTOR = 16;
  private static final double DEFAULT_MINIMUM_PEAK_RATIO = 1.5;
  private static final double DEFAULT_MINIMUM_NORMALIZED_CURVATURE = 1.5;

  private final double speedOfSoundMetersPerSecond;
  private final int interpolationFactor;
  private final double minimumPeakRatio;
  private final double minimumNormalizedCurvature;

  /** Creates the default 16-times interpolated estimator. */
  public SubSampleGccPhatTdoaEstimator(double speedOfSoundMetersPerSecond) {
    this(
        speedOfSoundMetersPerSecond,
        DEFAULT_INTERPOLATION_FACTOR,
        DEFAULT_MINIMUM_PEAK_RATIO,
        DEFAULT_MINIMUM_NORMALIZED_CURVATURE);
  }

  /** Creates an estimator with explicit interpolation and ambiguity thresholds. */
  public SubSampleGccPhatTdoaEstimator(
      double speedOfSoundMetersPerSecond,
      int interpolationFactor,
      double minimumPeakRatio,
      double minimumNormalizedCurvature) {
    this.speedOfSoundMetersPerSecond =
        requirePositiveFinite(speedOfSoundMetersPerSecond, "speedOfSoundMetersPerSecond");
    if (interpolationFactor < 2 || Integer.bitCount(interpolationFactor) != 1) {
      throw new IllegalArgumentException(
          "interpolationFactor must be a power of two greater than one");
    }
    this.interpolationFactor = interpolationFactor;
    this.minimumPeakRatio = requireAtLeastOne(minimumPeakRatio, "minimumPeakRatio");
    this.minimumNormalizedCurvature =
        requirePositiveFinite(minimumNormalizedCurvature, "minimumNormalizedCurvature");
  }

  @Override
  public DiagnosticTdoaEstimate estimateDetailed(
      AudioBlock block, MicrophoneArray array, int firstChannel, int secondChannel) {
    Objects.requireNonNull(block, "block");
    Objects.requireNonNull(array, "array");
    Microphone first = array.microphone(firstChannel);
    Microphone second = array.microphone(secondChannel);
    float[] firstSamples = block.channelView(firstChannel);
    float[] secondSamples = block.channelView(secondChannel);
    int frames = Math.min(firstSamples.length, secondSamples.length);
    int maximumLagSamples = Math.min(frames - 1, maximumPhysicalLag(block, first, second));
    double[] correlation =
        GccPhatCorrelation.correlate(firstSamples, secondSamples, frames, interpolationFactor);
    PeakSelection selection = selectPeak(correlation, maximumLagSamples);
    double delaySeconds = selection.interpolatedLagSamples() / block.format().sampleRate();
    TdoaEstimate estimate =
        new TdoaEstimate(
            first.id(),
            second.id(),
            (int) Math.round(selection.interpolatedLagSamples()),
            delaySeconds,
            delaySeconds * speedOfSoundMetersPerSecond,
            selection.confidence());
    return new DiagnosticTdoaEstimate(estimate, selection.diagnostics());
  }

  private PeakSelection selectPeak(double[] correlation, int maximumLagSamples) {
    int maximumLagUnits = Math.multiplyExact(maximumLagSamples, interpolationFactor);
    int bestLagUnits = 0;
    double primaryPeak = -1.0;
    for (int lagUnits = -maximumLagUnits; lagUnits <= maximumLagUnits; lagUnits++) {
      double score = score(correlation, lagUnits);
      if (score > primaryPeak) {
        primaryPeak = score;
        bestLagUnits = lagUnits;
      }
    }

    double secondaryPeak = 0.0;
    for (int lagUnits = -maximumLagUnits; lagUnits <= maximumLagUnits; lagUnits++) {
      if (Math.abs(lagUnits - bestLagUnits) <= interpolationFactor) {
        continue;
      }
      secondaryPeak = Math.max(secondaryPeak, score(correlation, lagUnits));
    }

    double left = score(correlation, bestLagUnits - 1);
    double right = score(correlation, bestLagUnits + 1);
    double fractionalUnit = parabolicOffset(left, primaryPeak, right);
    double interpolatedLagSamples = (bestLagUnits + fractionalUnit) / interpolationFactor;
    double peakRatio = primaryPeak > 0.0 ? primaryPeak / Math.max(secondaryPeak, EPSILON) : 0.0;
    double normalizedCurvature =
        Math.max(
            0.0,
            (2.0 * primaryPeak - left - right)
                / Math.max(primaryPeak, EPSILON)
                * interpolationFactor
                * interpolationFactor);
    boolean ambiguous =
        peakRatio < minimumPeakRatio || normalizedCurvature < minimumNormalizedCurvature;
    TdoaPeakDiagnostics diagnostics =
        new TdoaPeakDiagnostics(
            interpolatedLagSamples,
            primaryPeak,
            secondaryPeak,
            peakRatio,
            normalizedCurvature,
            ambiguous);
    double curvatureConfidence = Math.min(1.0, normalizedCurvature / minimumNormalizedCurvature);
    double confidence = Math.sqrt(diagnostics.separation() * curvatureConfidence);
    return new PeakSelection(interpolatedLagSamples, confidence, diagnostics);
  }

  private int maximumPhysicalLag(AudioBlock block, Microphone first, Microphone second) {
    double spacing = first.positionMeters().distanceTo(second.positionMeters());
    return (int) Math.ceil(spacing * block.format().sampleRate() / speedOfSoundMetersPerSecond);
  }

  private static double score(double[] correlation, int lagUnits) {
    int index = lagUnits >= 0 ? lagUnits : correlation.length + lagUnits;
    if (index < 0 || index >= correlation.length) {
      return 0.0;
    }
    return Math.abs(correlation[index]);
  }

  private static double parabolicOffset(double left, double center, double right) {
    double denominator = left - 2.0 * center + right;
    if (Math.abs(denominator) <= EPSILON) {
      return 0.0;
    }
    return Math.max(-0.5, Math.min(0.5, 0.5 * (left - right) / denominator));
  }

  private static double requirePositiveFinite(double value, String name) {
    if (Double.isFinite(value) && value > 0.0) {
      return value;
    }
    throw new IllegalArgumentException(name + " must be finite and > 0");
  }

  private static double requireAtLeastOne(double value, String name) {
    if (Double.isFinite(value) && value >= 1.0) {
      return value;
    }
    throw new IllegalArgumentException(name + " must be finite and >= 1");
  }

  private record PeakSelection(
      double interpolatedLagSamples, double confidence, TdoaPeakDiagnostics diagnostics) {}
}
