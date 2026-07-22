package org.hammer.audio.experimental.acoustic;

import org.hammer.audio.acquisition.Microphone;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.core.AudioBlock;

/**
 * Experimental integer-sample frequency-domain GCC-PHAT TDOA estimator.
 *
 * <p>This baseline zero-pads both channels, applies PHAT weighting and searches the strongest
 * physically plausible integer lag. It remains available beside the sub-sample diagnostic variant
 * for reproducible comparisons.
 */
public final class GccPhatTdoaEstimator implements TdoaEstimator {

  private final double speedOfSoundMetersPerSecond;

  /** Create a GCC-PHAT estimator with a propagation speed. */
  public GccPhatTdoaEstimator(double speedOfSoundMetersPerSecond) {
    this.speedOfSoundMetersPerSecond =
        requirePositiveFinite(speedOfSoundMetersPerSecond, "speedOfSoundMetersPerSecond");
  }

  @Override
  public TdoaEstimate estimate(
      AudioBlock block, MicrophoneArray array, int firstChannel, int secondChannel) {
    Microphone first = array.microphone(firstChannel);
    Microphone second = array.microphone(secondChannel);
    float[] firstSamples = block.channelView(firstChannel);
    float[] secondSamples = block.channelView(secondChannel);
    int frames = Math.min(firstSamples.length, secondSamples.length);
    int maximumLag = Math.min(frames - 1, maximumPhysicalLag(block, first, second));
    double[] correlation = GccPhatCorrelation.correlate(firstSamples, secondSamples, frames, 1);
    LagScore lagScore = strongestLag(correlation, maximumLag);
    double delaySeconds = lagScore.lag() / block.format().sampleRate();
    return new TdoaEstimate(
        first.id(),
        second.id(),
        lagScore.lag(),
        delaySeconds,
        delaySeconds * speedOfSoundMetersPerSecond,
        lagScore.confidence());
  }

  private int maximumPhysicalLag(AudioBlock block, Microphone first, Microphone second) {
    double spacing = first.positionMeters().distanceTo(second.positionMeters());
    return (int) Math.ceil(spacing * block.format().sampleRate() / speedOfSoundMetersPerSecond);
  }

  private static LagScore strongestLag(double[] correlation, int maximumLag) {
    int bestLag = 0;
    double bestScore = -1.0;
    double totalScore = 0.0;
    for (int lag = -maximumLag; lag <= maximumLag; lag++) {
      int index = lag >= 0 ? lag : correlation.length + lag;
      double score = Math.abs(correlation[index]);
      totalScore += score;
      if (score > bestScore) {
        bestScore = score;
        bestLag = lag;
      }
    }
    double confidence = totalScore > 0.0 ? bestScore / totalScore : 0.0;
    return new LagScore(bestLag, Math.min(1.0, Math.max(0.0, confidence)));
  }

  private static double requirePositiveFinite(double value, String name) {
    if (Double.isFinite(value) && value > 0.0) {
      return value;
    }
    throw new IllegalArgumentException(name + " must be finite and > 0");
  }

  private record LagScore(int lag, double confidence) {
    private LagScore {
      if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
        throw new IllegalArgumentException("confidence must be finite and in [0,1]");
      }
    }
  }
}
