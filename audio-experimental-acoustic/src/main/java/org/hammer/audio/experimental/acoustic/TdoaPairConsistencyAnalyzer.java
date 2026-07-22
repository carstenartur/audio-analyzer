package org.hammer.audio.experimental.acoustic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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

    PairAnalysis pairAnalysis =
        analyzePairs(array, requiredEstimates, sampleRate, physicalToleranceSeconds);
    CycleAnalysis cycleAnalysis =
        analyzeCycles(array.microphones(), pairAnalysis.delays(), cycleToleranceSeconds);
    List<TdoaConsistencyFinding> findings = new ArrayList<>(pairAnalysis.findings());
    findings.addAll(cycleAnalysis.findings());

    double meanResidual =
        cycleAnalysis.evaluatedCycles() > 0
            ? cycleAnalysis.residualTotal() / cycleAnalysis.evaluatedCycles()
            : 0.0;
    double consistencyScore =
        cycleAnalysis.evaluatedCycles() > 0 ? Math.exp(-meanResidual / cycleToleranceSeconds) : 1.0;
    if (pairAnalysis.physicalViolationCount() > 0) {
      consistencyScore = 0.0;
    }
    return new TdoaConsistencyReport(
        findings,
        cycleAnalysis.evaluatedCycles(),
        meanResidual,
        cycleAnalysis.maximumResidual(),
        pairAnalysis.physicalViolationCount(),
        consistencyScore);
  }

  private PairAnalysis analyzePairs(
      MicrophoneArray array,
      List<TdoaEstimate> estimates,
      float sampleRate,
      double physicalToleranceSeconds) {
    List<OrientedDelay> delays = new ArrayList<>(estimates.size() * 2);
    Set<UnorderedPair> seenPairs = new HashSet<>();
    List<TdoaConsistencyFinding> findings = new ArrayList<>();
    for (TdoaEstimate estimate : estimates) {
      Microphone first = microphone(array, estimate.firstMicrophoneId());
      Microphone second = microphone(array, estimate.secondMicrophoneId());
      UnorderedPair pair = UnorderedPair.of(first.id(), second.id());
      if (seenPairs.add(pair)) {
        delays.add(new OrientedDelay(first.id(), second.id(), estimate.delaySeconds()));
        delays.add(new OrientedDelay(second.id(), first.id(), -estimate.delaySeconds()));
      } else {
        throw new IllegalArgumentException("duplicate TDOA estimate for pair " + pair);
      }
      TdoaConsistencyFinding finding =
          physicalFinding(first, second, estimate, sampleRate, physicalToleranceSeconds);
      if (finding != null) {
        findings.add(finding);
      }
    }
    return new PairAnalysis(List.copyOf(delays), findings, findings.size());
  }

  private TdoaConsistencyFinding physicalFinding(
      Microphone first,
      Microphone second,
      TdoaEstimate estimate,
      float sampleRate,
      double physicalToleranceSeconds) {
    double physicalLimit =
        first.positionMeters().distanceTo(second.positionMeters()) / speedOfSoundMetersPerSecond
            + physicalToleranceSeconds;
    double excess = Math.abs(estimate.delaySeconds()) - physicalLimit;
    if (excess <= 0.0) {
      return null;
    }
    double tolerance = physicalToleranceSeconds > 0.0 ? physicalToleranceSeconds : 1.0 / sampleRate;
    return new TdoaConsistencyFinding(
        TdoaConsistencyFinding.Kind.PHYSICAL_LIMIT,
        List.of(first.id(), second.id()),
        Math.copySign(excess, estimate.delaySeconds()),
        tolerance);
  }

  private static CycleAnalysis analyzeCycles(
      List<Microphone> microphones,
      List<OrientedDelay> delays,
      double cycleToleranceSeconds) {
    List<TdoaConsistencyFinding> findings = new ArrayList<>();
    int evaluatedCycles = 0;
    double residualTotal = 0.0;
    double maximumResidual = 0.0;
    for (int firstIndex = 0; firstIndex < microphones.size(); firstIndex++) {
      for (int secondIndex = firstIndex + 1; secondIndex < microphones.size(); secondIndex++) {
        for (int thirdIndex = secondIndex + 1; thirdIndex < microphones.size(); thirdIndex++) {
          String firstId = microphones.get(firstIndex).id();
          String secondId = microphones.get(secondIndex).id();
          String thirdId = microphones.get(thirdIndex).id();
          Double firstSecond = delay(delays, firstId, secondId);
          Double secondThird = delay(delays, secondId, thirdId);
          Double firstThird = delay(delays, firstId, thirdId);
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
    return new CycleAnalysis(findings, evaluatedCycles, residualTotal, maximumResidual);
  }

  private static Double delay(List<OrientedDelay> delays, String firstId, String secondId) {
    for (OrientedDelay delay : delays) {
      if (delay.firstId().equals(firstId) && delay.secondId().equals(secondId)) {
        return delay.delaySeconds();
      }
    }
    return null;
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

  private record PairAnalysis(
      List<OrientedDelay> delays,
      List<TdoaConsistencyFinding> findings,
      int physicalViolationCount) {
    // immutable analysis tuple
  }

  private record CycleAnalysis(
      List<TdoaConsistencyFinding> findings,
      int evaluatedCycles,
      double residualTotal,
      double maximumResidual) {
    // immutable analysis tuple
  }

  private record OrientedDelay(String firstId, String secondId, double delaySeconds) {
    private OrientedDelay {
      if (firstId == null || firstId.isBlank() || secondId == null || secondId.isBlank()) {
        throw new IllegalArgumentException("delay ids must not be blank");
      }
      if (firstId.equals(secondId)) {
        throw new IllegalArgumentException("delay ids must be distinct");
      }
      if (!Double.isFinite(delaySeconds)) {
        throw new IllegalArgumentException("delaySeconds must be finite");
      }
    }
  }

  private record UnorderedPair(String firstId, String secondId) {
    private UnorderedPair {
      if (firstId == null || firstId.isBlank() || secondId == null || secondId.isBlank()) {
        throw new IllegalArgumentException("pair ids must not be blank");
      }
      if (firstId.equals(secondId)) {
        throw new IllegalArgumentException("pair ids must be distinct");
      }
    }

    private static UnorderedPair of(String firstId, String secondId) {
      return firstId.compareTo(secondId) <= 0
          ? new UnorderedPair(firstId, secondId)
          : new UnorderedPair(secondId, firstId);
    }
  }
}
