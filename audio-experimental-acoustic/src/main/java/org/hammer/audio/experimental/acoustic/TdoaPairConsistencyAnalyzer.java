package org.hammer.audio.experimental.acoustic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.acquisition.Microphone;
import org.hammer.audio.acquisition.MicrophoneArray;

/** Detects physically impossible and triangle-inconsistent pairwise TDOA estimates. */
public final class TdoaPairConsistencyAnalyzer {

  private final double speedOfSoundMetersPerSecond;
  private final double cycleToleranceSamples;
  private final double physicalToleranceSamples;

  /** Creates an analyzer with tolerances expressed in samples of the analysed stream. */
  public TdoaPairConsistencyAnalyzer(
      double speedOfSoundMetersPerSecond,
      double cycleToleranceSamples,
      double physicalToleranceSamples) {
    this.speedOfSoundMetersPerSecond =
        requirePositiveFinite(speedOfSoundMetersPerSecond, "speedOfSoundMetersPerSecond");
    this.cycleToleranceSamples =
        requirePositiveFinite(cycleToleranceSamples, "cycleToleranceSamples");
    this.physicalToleranceSamples =
        requireNonNegativeFinite(physicalToleranceSamples, "physicalToleranceSamples");
  }

  /** Analyzes one ordered set of pair estimates at the supplied sample rate. */
  public TdoaConsistencyReport analyze(
      MicrophoneArray array, List<TdoaEstimate> estimates, float sampleRate) {
    Objects.requireNonNull(array, "array");
    List<TdoaEstimate> requiredEstimates =
        List.copyOf(Objects.requireNonNull(estimates, "estimates"));
    requirePositiveFinite(sampleRate, "sampleRate");
    double cycleToleranceSeconds = cycleToleranceSamples / sampleRate;
    double physicalToleranceSeconds = physicalToleranceSamples / sampleRate;
    Map<OrientedPair, Double> delays = new HashMap<>();
    List<TdoaConsistencyFinding> findings = new ArrayList<>();
    int physicalViolations = 0;

    for (TdoaEstimate estimate : requiredEstimates) {
      Microphone first = microphone(array, estimate.firstMicrophoneId());
      Microphone second = microphone(array, estimate.secondMicrophoneId());
      OrientedPair forward = new OrientedPair(first.id(), second.id());
      OrientedPair reverse = new OrientedPair(second.id(), first.id());
      if (delays.putIfAbsent(forward, estimate.delaySeconds()) != null
          || delays.putIfAbsent(reverse, -estimate.delaySeconds()) != null) {
        throw new IllegalArgumentException("duplicate TDOA estimate for pair " + forward);
      }
      double physicalLimit =
          first.positionMeters().distanceTo(second.positionMeters()) / speedOfSoundMetersPerSecond
              + physicalToleranceSeconds;
      double excess = Math.abs(estimate.delaySeconds()) - physicalLimit;
      if (excess > 0.0) {
        physicalViolations++;
        findings.add(
            new TdoaConsistencyFinding(
                TdoaConsistencyFinding.Kind.PHYSICAL_LIMIT,
                List.of(first.id(), second.id()),
                Math.copySign(excess, estimate.delaySeconds()),
                physicalToleranceSeconds > 0.0 ? physicalToleranceSeconds : 1.0 / sampleRate));
      }
    }

    int evaluatedCycles = 0;
    double residualTotal = 0.0;
    double maximumResidual = 0.0;
    List<Microphone> microphones = array.microphones();
    for (int firstIndex = 0; firstIndex < microphones.size(); firstIndex++) {
      for (int secondIndex = firstIndex + 1; secondIndex < microphones.size(); secondIndex++) {
        for (int thirdIndex = secondIndex + 1; thirdIndex < microphones.size(); thirdIndex++) {
          String firstId = microphones.get(firstIndex).id();
          String secondId = microphones.get(secondIndex).id();
          String thirdId = microphones.get(thirdIndex).id();
          Double firstSecond = delays.get(new OrientedPair(firstId, secondId));
          Double secondThird = delays.get(new OrientedPair(secondId, thirdId));
          Double firstThird = delays.get(new OrientedPair(firstId, thirdId));
          if (firstSecond == null || secondThird == null || firstThird == null) {
            continue;
          }
          double residual = firstSecond + secondThird - firstThird;
          double absoluteResidual = Math.abs(residual);
          evaluatedCycles++;
          residualTotal += absoluteResidual;
          maximumResidual = Math.max(maximumResidual, absoluteResidual);
          if (absoluteResidual > cycleToleranceSeconds) {
            findings.add(
                new TdoaConsistencyFinding(
                    TdoaConsistencyFinding.Kind.CYCLE_RESIDUAL,
                    List.of(firstId, secondId, thirdId),
                    residual,
                    cycleToleranceSeconds));
          }
        }
      }
    }

    double meanResidual = evaluatedCycles > 0 ? residualTotal / evaluatedCycles : 0.0;
    double consistencyScore =
        evaluatedCycles > 0 ? Math.exp(-meanResidual / cycleToleranceSeconds) : 1.0;
    if (physicalViolations > 0) {
      consistencyScore = 0.0;
    }
    return new TdoaConsistencyReport(
        findings,
        evaluatedCycles,
        meanResidual,
        maximumResidual,
        physicalViolations,
        consistencyScore);
  }

  private static Microphone microphone(MicrophoneArray array, String id) {
    return array.microphones().stream()
        .filter(microphone -> microphone.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown microphone id: " + id));
  }

  private static double requirePositiveFinite(double value, String name) {
    if (Double.isFinite(value) && value > 0.0) {
      return value;
    }
    throw new IllegalArgumentException(name + " must be finite and > 0");
  }

  private static double requireNonNegativeFinite(double value, String name) {
    if (Double.isFinite(value) && value >= 0.0) {
      return value;
    }
    throw new IllegalArgumentException(name + " must be finite and >= 0");
  }

  private record OrientedPair(String firstId, String secondId) {
    private OrientedPair {
      if (firstId == null || firstId.isBlank() || secondId == null || secondId.isBlank()) {
        throw new IllegalArgumentException("pair ids must not be blank");
      }
      if (firstId.equals(secondId)) {
        throw new IllegalArgumentException("pair ids must be distinct");
      }
    }
  }
}
