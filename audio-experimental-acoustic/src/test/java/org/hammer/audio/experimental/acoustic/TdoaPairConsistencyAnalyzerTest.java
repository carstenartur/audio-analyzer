package org.hammer.audio.experimental.acoustic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.experimental.acoustic.TdoaConsistencyFinding.Kind;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios;
import org.junit.jupiter.api.Test;

class TdoaPairConsistencyAnalyzerTest {

  private static final float SAMPLE_RATE = 48_000.0f;
  private static final double SPEED_OF_SOUND = 343.0;

  @Test
  void acceptsCompletePhysicallyConsistentPairSet() {
    TdoaConsistencyReport report = analyzer().analyze(array(), consistentEstimates(), SAMPLE_RATE);

    assertEquals(4, report.evaluatedCycles());
    assertTrue(report.findings().isEmpty());
    assertEquals(0.0, report.meanAbsoluteCycleResidualSeconds(), 1.0e-15);
    assertEquals(1.0, report.consistencyScore(), 1.0e-12);
    assertTrue(report.reliable());
    assertEquals(1.0, report.confidenceMultiplier(), 1.0e-12);
  }

  @Test
  void detectsCycleInconsistencyAndPenalizesConfidence() {
    List<TdoaEstimate> estimates = new ArrayList<>(consistentEstimates());
    TdoaEstimate original = estimates.get(2);
    estimates.set(
        2,
        estimate(
            original.firstMicrophoneId(),
            original.secondMicrophoneId(),
            original.delaySeconds() + 3.0 / SAMPLE_RATE));

    TdoaConsistencyReport report = analyzer().analyze(array(), estimates, SAMPLE_RATE);

    assertTrue(report.findings().stream().anyMatch(finding -> finding.kind() == Kind.CYCLE_RESIDUAL));
    assertTrue(report.maximumAbsoluteCycleResidualSeconds() >= 3.0 / SAMPLE_RATE - 1.0e-12);
    assertTrue(report.consistencyScore() < 0.5);
    assertFalse(report.reliable());
    assertTrue(report.confidenceMultiplier() < 0.5);
  }

  @Test
  void rejectsPairOutsidePhysicalPropagationLimit() {
    List<TdoaEstimate> estimates = new ArrayList<>(consistentEstimates());
    estimates.set(0, estimate("m0", "m1", 0.002));

    TdoaConsistencyReport report = analyzer().analyze(array(), estimates, SAMPLE_RATE);

    assertEquals(1, report.physicalViolationCount());
    assertTrue(report.findings().stream().anyMatch(finding -> finding.kind() == Kind.PHYSICAL_LIMIT));
    assertEquals(0.0, report.confidenceMultiplier(), 0.0);
    assertFalse(report.reliable());
  }

  private static TdoaPairConsistencyAnalyzer analyzer() {
    return new TdoaPairConsistencyAnalyzer(SPEED_OF_SOUND, 0.5, 0.25);
  }

  private static MicrophoneArray array() {
    return SimulationScenarios.defaultArray();
  }

  private static List<TdoaEstimate> consistentEstimates() {
    double[] arrivals = {0.0, 0.0002, -0.0001, 0.00005};
    List<TdoaEstimate> estimates = new ArrayList<>();
    for (int first = 0; first < arrivals.length; first++) {
      for (int second = first + 1; second < arrivals.length; second++) {
        estimates.add(estimate("m" + first, "m" + second, arrivals[second] - arrivals[first]));
      }
    }
    return List.copyOf(estimates);
  }

  private static TdoaEstimate estimate(String firstId, String secondId, double delaySeconds) {
    return new TdoaEstimate(
        firstId,
        secondId,
        (int) Math.round(delaySeconds * SAMPLE_RATE),
        delaySeconds,
        delaySeconds * SPEED_OF_SOUND,
        0.9);
  }
}
