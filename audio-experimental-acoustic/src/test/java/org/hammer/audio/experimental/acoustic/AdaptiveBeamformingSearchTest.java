package org.hammer.audio.experimental.acoustic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.experimental.acoustic.AdaptiveBeamformingSearch.BeamformingSearchResult;
import org.hammer.audio.experimental.acoustic.AdaptiveBeamformingSearch.SearchBounds;
import org.hammer.audio.experimental.acoustic.DelayAndSumBeamformer.BeamformingPoint;
import org.hammer.audio.experimental.acoustic.simulation.AcousticEmitter2D;
import org.hammer.audio.experimental.acoustic.simulation.Room2D;
import org.hammer.audio.experimental.acoustic.simulation.SimulatedMicrophoneArraySource;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios.SimulationScenario;
import org.hammer.audio.geometry.Vector2;
import org.junit.jupiter.api.Test;

class AdaptiveBeamformingSearchTest {

  private static final double SPEED_OF_SOUND = 343.0;
  private static final Vector2 SOURCE_POSITION = new Vector2(1.43, 1.17);

  @Test
  void matchesFineGridAccuracyWithFarFewerCandidateEvaluations() throws Exception {
    SimulationScenario scenario = chirpScenario();
    AudioBlock block;
    try (SimulatedMicrophoneArraySource source = scenario.newSource()) {
      block = source.readBlock(2_048).orElseThrow();
    }
    DelayAndSumBeamformer beamformer = new DelayAndSumBeamformer(SPEED_OF_SOUND);
    SearchBounds bounds = new SearchBounds(0.0, 3.0, 0.0, 2.0);

    List<Vector2> fineGrid = bounds.grid(32);
    BeamformingPoint uniformBest = beamformer.best(block, scenario.array(), fineGrid);
    BeamformingSearchResult adaptive =
        new AdaptiveBeamformingSearch(beamformer).search(block, scenario.array(), bounds, 4, 4);

    double uniformError = uniformBest.positionMeters().distanceTo(SOURCE_POSITION);
    double adaptiveError = adaptive.best().positionMeters().distanceTo(SOURCE_POSITION);
    assertTrue(uniformError < 0.15, "fine-grid error=" + uniformError);
    assertTrue(
        adaptiveError <= uniformError + 0.02,
        "adaptive error=" + adaptiveError + ", fine-grid error=" + uniformError);
    assertTrue(adaptiveError < 0.15, "adaptive error=" + adaptiveError);
    assertTrue(
        adaptive.evaluatedCandidateCount() < fineGrid.size() / 5,
        "adaptive candidates="
            + adaptive.evaluatedCandidateCount()
            + ", fine-grid candidates="
            + fineGrid.size());
    assertEquals(
        adaptive.evaluatedCandidateCount(), adaptive.normalizedConfidenceSurface().size());
    assertTrue(
        adaptive.normalizedConfidenceSurface().stream()
            .anyMatch(point -> Math.abs(point.normalizedConfidence() - 1.0) < 1.0e-12));
  }

  @Test
  void producesDeterministicRefinementAndSurfaceOrdering() throws Exception {
    SimulationScenario scenario = chirpScenario();
    AudioBlock block;
    try (SimulatedMicrophoneArraySource source = scenario.newSource()) {
      block = source.readBlock(1_024).orElseThrow();
    }
    AdaptiveBeamformingSearch search =
        new AdaptiveBeamformingSearch(new DelayAndSumBeamformer(SPEED_OF_SOUND));
    SearchBounds bounds = new SearchBounds(0.0, 3.0, 0.0, 2.0);

    BeamformingSearchResult first = search.search(block, scenario.array(), bounds, 4, 3);
    BeamformingSearchResult second = search.search(block, scenario.array(), bounds, 4, 3);

    assertEquals(first.best(), second.best());
    assertEquals(first.evaluatedPoints(), second.evaluatedPoints());
  }

  @Test
  void retainsGlobalMaximumAndNormalizedSurfaceWithOddGridSteps() throws Exception {
    Vector2 coarseGridPoint = new Vector2(1.0, 2.0 / 3.0);
    SimulationScenario scenario = chirpScenario(coarseGridPoint, "odd-grid-beamforming-chirp");
    AudioBlock block;
    try (SimulatedMicrophoneArraySource source = scenario.newSource()) {
      block = source.readBlock(2_048).orElseThrow();
    }
    BeamformingSearchResult result =
        new AdaptiveBeamformingSearch(new DelayAndSumBeamformer(SPEED_OF_SOUND))
            .search(
                block,
                scenario.array(),
                new SearchBounds(0.0, 3.0, 0.0, 2.0),
                3,
                3);

    double maximumEvaluatedEnergy =
        result.evaluatedPoints().stream()
            .mapToDouble(BeamformingPoint::energy)
            .max()
            .orElseThrow();
    assertEquals(maximumEvaluatedEnergy, result.best().energy(), 1.0e-12);
    assertTrue(
        result.normalizedConfidenceSurface().stream()
            .allMatch(
                point ->
                    point.normalizedConfidence() >= 0.0
                        && point.normalizedConfidence() <= 1.0));
    assertTrue(
        result.normalizedConfidenceSurface().stream()
            .anyMatch(point -> Math.abs(point.normalizedConfidence() - 1.0) < 1.0e-12));
  }

  private static SimulationScenario chirpScenario() {
    return chirpScenario(SOURCE_POSITION, "adaptive-beamforming-chirp");
  }

  private static SimulationScenario chirpScenario(Vector2 sourcePosition, String name) {
    AcousticEmitter2D emitter =
        new AcousticEmitter2D() {
          @Override
          public Vector2 startMeters() {
            return sourcePosition;
          }

          @Override
          public Vector2 velocityMetersPerSecond() {
            return Vector2.ZERO;
          }

          @Override
          public double frequencyHz() {
            return 800.0;
          }

          @Override
          public double amplitude() {
            return 0.7;
          }

          @Override
          public double sampleAt(double seconds) {
            double phase = 2.0 * Math.PI * (400.0 * seconds + 4_000.0 * seconds * seconds);
            return amplitude() * Math.sin(phase);
          }

          @Override
          public double sampleAt(double seconds, double observedFrequencyHz) {
            return sampleAt(seconds) * Math.min(1.0, observedFrequencyHz / frequencyHz());
          }
        };
    return new SimulationScenario(
        name,
        new Room2D(3.0, 2.0, 0.0, 0.0),
        SimulationScenarios.defaultArray(),
        List.of(emitter),
        16_000.0f,
        0.25,
        138L);
  }
}
