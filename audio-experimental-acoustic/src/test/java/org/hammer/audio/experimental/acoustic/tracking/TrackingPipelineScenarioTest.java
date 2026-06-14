package org.hammer.audio.experimental.acoustic.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.experimental.acoustic.DelayAndSumBeamformer;
import org.hammer.audio.experimental.acoustic.FrequencyBand;
import org.hammer.audio.experimental.acoustic.GccPhatTdoaEstimator;
import org.hammer.audio.experimental.acoustic.simulation.SimulatedMicrophoneArraySource;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios.SimulationScenario;
import org.hammer.audio.geometry.Vector2;
import org.junit.jupiter.api.Test;

/**
 * End-to-end validation of the {@link TrackingPipeline} against the deterministic scenarios in
 * {@link SimulationScenarios}.
 *
 * <p>These tests do not assert sub-centimeter localization accuracy (the simulator and pipeline are
 * research grade), but they assert the structural guarantees the platform makes:
 *
 * <ul>
 *   <li>The pipeline produces deterministic snapshots for a fixed seed.
 *   <li>For a single source the tracker converges to a single stable track id.
 *   <li>For two distinct frequencies it produces two tracks with distinct ids.
 *   <li>Processing time stays well below the per-block timing budget for the chosen schedule.
 * </ul>
 */
class TrackingPipelineScenarioTest {

  @Test
  void singleSourceProducesOneStableTrack() {
    SimulationScenario scenario = SimulationScenarios.singleSource();
    List<TrackingSnapshot> snapshots = run(scenario, 1024);

    assertTrue(!snapshots.isEmpty(), "expected at least one processed block");
    TrackingSnapshot last = snapshots.get(snapshots.size() - 1);
    assertTrue(!last.tracks().isEmpty(), "expected at least one tracked source");
    // The dominant track (highest observationCount) should keep its id across the second half of
    // the run. Spurious low-observation tracks may briefly appear and are tolerated.
    int dominantId = dominantTrack(last).id();
    int matchCount = 0;
    List<TrackingSnapshot> tail = snapshots.subList(snapshots.size() / 2, snapshots.size());
    for (TrackingSnapshot snapshot : tail) {
      if (snapshot.tracks().isEmpty()) {
        continue;
      }
      if (dominantTrack(snapshot).id() == dominantId) {
        matchCount++;
      }
    }
    assertTrue(
        matchCount > tail.size() / 2,
        "dominant track id should be stable across most tail frames, matched "
            + matchCount
            + "/"
            + tail.size());
  }

  private static TrackedSource dominantTrack(TrackingSnapshot snapshot) {
    TrackedSource best = snapshot.tracks().get(0);
    for (TrackedSource track : snapshot.tracks()) {
      if (track.observationCount() > best.observationCount()) {
        best = track;
      }
    }
    return best;
  }

  @Test
  void twoCloseFrequenciesProduceTwoDistinctTracks() {
    SimulationScenario scenario = SimulationScenarios.twoCloseFrequencies();
    List<TrackingSnapshot> snapshots = run(scenario, 2048);

    int maxTracks = 0;
    for (TrackingSnapshot snapshot : snapshots) {
      maxTracks = Math.max(maxTracks, snapshot.tracks().size());
    }
    assertTrue(
        maxTracks >= 2, "expected pipeline to track both sources at some point, max=" + maxTracks);
  }

  @Test
  void deterministicForFixedSeed() {
    SimulationScenario scenario = SimulationScenarios.singleSource();
    List<TrackingSnapshot> first = run(scenario, 1024);
    List<TrackingSnapshot> second = run(scenario, 1024);
    assertEquals(first.size(), second.size());
    for (int i = 0; i < first.size(); i++) {
      assertEquals(first.get(i).clusters().size(), second.get(i).clusters().size());
      assertEquals(first.get(i).tracks().size(), second.get(i).tracks().size());
    }
  }

  @Test
  void processingFitsInRealtimeBudget() {
    SimulationScenario scenario = SimulationScenarios.singleSource();
    int blockFrames = 1024;
    FrameSchedule schedule = new FrameSchedule(scenario.sampleRate(), blockFrames, 0.8);
    List<TrackingSnapshot> snapshots = run(scenario, blockFrames);

    for (TrackingSnapshot snapshot : snapshots) {
      assertTrue(
          snapshot.processingNanos() < schedule.maxProcessingNanos() * 4L,
          "single-block processing time "
              + snapshot.processingNanos()
              + " ns should be within a small multiple of the realtime budget "
              + schedule.maxProcessingNanos());
    }
  }

  @Test
  void noisyAndReflectedScenariosKeepDetectingTheSource() {
    for (SimulationScenario scenario :
        List.of(SimulationScenarios.noisyRoom(), SimulationScenarios.reflectedEnvironment())) {
      List<TrackingSnapshot> snapshots = run(scenario, 2048);
      int framesWithTrack = 0;
      for (TrackingSnapshot snapshot : snapshots) {
        if (!snapshot.tracks().isEmpty()) {
          framesWithTrack++;
        }
      }
      assertTrue(
          framesWithTrack > snapshots.size() / 4,
          scenario.name()
              + " should detect a source in most frames, got "
              + framesWithTrack
              + "/"
              + snapshots.size());
    }
  }

  @Test
  void movingSourceUpdatesPositionEstimateOverTime() {
    SimulationScenario scenario = SimulationScenarios.movingSource();
    List<TrackingSnapshot> snapshots = run(scenario, 2048);

    Vector2 firstPosition = null;
    Vector2 lastPosition = null;
    for (TrackingSnapshot snapshot : snapshots) {
      if (snapshot.tracks().isEmpty()) {
        continue;
      }
      TrackedSource dominant = dominantTrack(snapshot);
      if (firstPosition == null) {
        firstPosition = dominant.positionMeters();
      }
      lastPosition = dominant.positionMeters();
    }
    assertTrue(firstPosition != null && lastPosition != null);
    assertTrue(
        firstPosition.distanceTo(lastPosition) > 0.05,
        "moving source should produce position change, first="
            + firstPosition
            + " last="
            + lastPosition);
  }

  private static List<TrackingSnapshot> run(SimulationScenario scenario, int blockFrames) {
    SimulatedMicrophoneArraySource source = scenario.newSource();
    MicrophoneArray array = source.microphoneArray();
    MultiPeakDetector detector =
        new MultiPeakDetector(blockFrames, new FrequencyBand(150.0, 2_500.0), 3, 2.0);
    FrequencyClusterer clusterer = new FrequencyClusterer(25.0, 0.0, 2, 4);
    GccPhatTdoaEstimator tdoaEstimator = new GccPhatTdoaEstimator(343.0);
    DelayAndSumBeamformer beamformer = new DelayAndSumBeamformer(343.0);
    SourceTracker tracker = new SourceTracker(35.0, 4, 0.5, 0.04, 1.0, 1.0, 0.85, 0.4);
    List<Vector2> grid = candidateGrid(scenario);
    FrameSchedule schedule = new FrameSchedule(scenario.sampleRate(), blockFrames, 0.8);
    TrackingPipeline pipeline =
        new TrackingPipeline(
            detector, clusterer, tdoaEstimator, beamformer, tracker, grid, schedule);

    List<TrackingSnapshot> snapshots = new ArrayList<>();
    while (true) {
      AudioBlock block = source.readBlock(blockFrames).orElse(null);
      if (block == null || block.frames() < blockFrames) {
        break;
      }
      snapshots.add(pipeline.process(block, array));
    }
    return snapshots;
  }

  private static List<Vector2> candidateGrid(SimulationScenario scenario) {
    List<Vector2> grid = new ArrayList<>();
    double width = scenario.room().widthMeters();
    double height = scenario.room().heightMeters();
    int steps = 8;
    for (int xi = 0; xi <= steps; xi++) {
      for (int yi = 0; yi <= steps; yi++) {
        grid.add(new Vector2(width * xi / steps, height * yi / steps));
      }
    }
    return grid;
  }
}
