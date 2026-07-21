package org.hammer.audio.experimental.acoustic.calibration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.acquisition.ChannelTimingCalibration;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.acquisition.MicrophoneArrayCalibration;
import org.hammer.audio.core.AudioBlock;

/** Deterministic normalized-correlation estimator for microphone timing calibration. */
public final class ArrayTimingCalibrationEstimator {

  private final int maximumLagSamples;
  private final double minimumConfidence;
  private final double maximumAbsoluteDriftPpm;

  /** Creates an estimator with explicit search and validity bounds. */
  public ArrayTimingCalibrationEstimator(
      int maximumLagSamples, double minimumConfidence, double maximumAbsoluteDriftPpm) {
    if (maximumLagSamples < 1) {
      throw new IllegalArgumentException("maximumLagSamples must be >= 1");
    }
    if (!Double.isFinite(minimumConfidence) || minimumConfidence < 0.0 || minimumConfidence > 1.0) {
      throw new IllegalArgumentException("minimumConfidence must be in [0,1]");
    }
    if (!(maximumAbsoluteDriftPpm > 0.0) || !Double.isFinite(maximumAbsoluteDriftPpm)) {
      throw new IllegalArgumentException("maximumAbsoluteDriftPpm must be finite and > 0");
    }
    this.maximumLagSamples = maximumLagSamples;
    this.minimumConfidence = minimumConfidence;
    this.maximumAbsoluteDriftPpm = maximumAbsoluteDriftPpm;
  }

  /** Estimates channel delays for one common calibration event. */
  public CalibrationEventObservation observe(AudioBlock block, int referenceChannel) {
    Objects.requireNonNull(block, "block");
    if (referenceChannel < 0 || referenceChannel >= block.channels()) {
      throw new IllegalArgumentException("referenceChannel must exist in audio block");
    }
    List<Double> offsets = new ArrayList<>(block.channels());
    List<Double> confidences = new ArrayList<>(block.channels());
    float[] reference = block.channelView(referenceChannel);
    for (int channel = 0; channel < block.channels(); channel++) {
      if (channel == referenceChannel) {
        offsets.add(0.0);
        confidences.add(1.0);
        continue;
      }
      LagScore score = strongestLag(reference, block.channelView(channel));
      if (score.confidence() < minimumConfidence) {
        throw new IllegalArgumentException(
            "Calibration correlation confidence below threshold for channel " + channel);
      }
      offsets.add((double) score.lag());
      confidences.add(score.confidence());
    }
    return new CalibrationEventObservation(
        block.frameIndex(), referenceChannel, offsets, confidences);
  }

  /** Derives a complete affine timing profile from two repeated calibration events. */
  public MicrophoneArrayCalibration calibrate(
      String profileId,
      MicrophoneArray array,
      CalibrationEventObservation first,
      CalibrationEventObservation second,
      Instant calibratedAt,
      Instant validUntil) {
    Objects.requireNonNull(array, "array");
    Objects.requireNonNull(first, "first");
    Objects.requireNonNull(second, "second");
    if (first.referenceChannel() != second.referenceChannel()) {
      throw new IllegalArgumentException("calibration events must use the same reference channel");
    }
    if (first.channels() != array.channels() || second.channels() != array.channels()) {
      throw new IllegalArgumentException("calibration events must cover the microphone array");
    }
    long elapsedFrames = second.frameIndex() - first.frameIndex();
    if (elapsedFrames <= 0) {
      throw new IllegalArgumentException("second calibration event must be later than first");
    }
    List<ChannelTimingCalibration> channels = new ArrayList<>(array.channels());
    for (int channel = 0; channel < array.channels(); channel++) {
      double firstOffset = first.offsetsSamples().get(channel);
      double secondOffset = second.offsetsSamples().get(channel);
      double driftPpm = (secondOffset - firstOffset) * 1.0e6 / elapsedFrames;
      if (Math.abs(driftPpm) > maximumAbsoluteDriftPpm) {
        throw new IllegalArgumentException(
            "Estimated drift exceeds configured bound for channel " + channel);
      }
      double confidence =
          Math.min(first.confidences().get(channel), second.confidences().get(channel));
      double residualEstimate = 1.0 - confidence;
      if (channel == first.referenceChannel()) {
        channels.add(
            new ChannelTimingCalibration(
                channel, first.frameIndex(), 0.0, 0.0, 0.0, 0.0, 1.0, false));
      } else {
        channels.add(
            new ChannelTimingCalibration(
                channel,
                first.frameIndex(),
                firstOffset,
                driftPpm,
                residualEstimate,
                0.0,
                1.0,
                false));
      }
    }
    return new MicrophoneArrayCalibration(
        profileId, array, first.referenceChannel(), channels, calibratedAt, validUntil);
  }

  private LagScore strongestLag(float[] reference, float[] channel) {
    int frames = Math.min(reference.length, channel.length);
    int maximumLag = Math.min(maximumLagSamples, frames - 1);
    int bestLag = 0;
    double bestCorrelation = 0.0;
    for (int lag = -maximumLag; lag <= maximumLag; lag++) {
      double correlation = normalizedCorrelation(reference, channel, frames, lag);
      if (Math.abs(correlation) > Math.abs(bestCorrelation)) {
        bestCorrelation = correlation;
        bestLag = lag;
      }
    }
    return new LagScore(bestLag, Math.min(1.0, Math.abs(bestCorrelation)));
  }

  private static double normalizedCorrelation(float[] first, float[] second, int frames, int lag) {
    int firstStart = Math.max(0, -lag);
    int secondStart = Math.max(0, lag);
    int overlap = frames - Math.abs(lag);
    double sum = 0.0;
    double firstEnergy = 0.0;
    double secondEnergy = 0.0;
    for (int index = 0; index < overlap; index++) {
      double firstValue = first[firstStart + index];
      double secondValue = second[secondStart + index];
      sum += firstValue * secondValue;
      firstEnergy += firstValue * firstValue;
      secondEnergy += secondValue * secondValue;
    }
    double denominator = Math.sqrt(firstEnergy * secondEnergy);
    return denominator > 0.0 ? sum / denominator : 0.0;
  }

  private record LagScore(int lag, double confidence) {
    private LagScore {
      if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
        throw new IllegalArgumentException("confidence must be finite and in [0,1]");
      }
    }
  }
}
