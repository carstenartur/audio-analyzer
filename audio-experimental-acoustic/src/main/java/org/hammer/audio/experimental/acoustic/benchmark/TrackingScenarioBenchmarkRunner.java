package org.hammer.audio.experimental.acoustic.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.experimental.acoustic.DelayAndSumBeamformer;
import org.hammer.audio.experimental.acoustic.FrequencyBand;
import org.hammer.audio.experimental.acoustic.GccPhatTdoaEstimator;
import org.hammer.audio.experimental.acoustic.scenario.Scenario;
import org.hammer.audio.experimental.acoustic.simulation.SimulatedMicrophoneArraySource;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios.SimulationScenario;
import org.hammer.audio.experimental.acoustic.tracking.FrameSchedule;
import org.hammer.audio.experimental.acoustic.tracking.FrequencyClusterer;
import org.hammer.audio.experimental.acoustic.tracking.MultiPeakDetector;
import org.hammer.audio.experimental.acoustic.tracking.SourceTracker;
import org.hammer.audio.experimental.acoustic.tracking.TrackingPipeline;
import org.hammer.audio.experimental.acoustic.tracking.TrackingSnapshot;
import org.hammer.audio.geometry.Vector2;

/** Run the current tracking pipeline against simulation scenarios and emit benchmark reports. */
public final class TrackingScenarioBenchmarkRunner {

  private final int blockFrames;
  private final TrackingBenchmarkComparator comparator;

  public TrackingScenarioBenchmarkRunner(int blockFrames) {
    this(blockFrames, new TrackingBenchmarkComparator());
  }

  public TrackingScenarioBenchmarkRunner(int blockFrames, TrackingBenchmarkComparator comparator) {
    if (blockFrames < 128) {
      throw new IllegalArgumentException("blockFrames must be >= 128");
    }
    this.blockFrames = blockFrames;
    this.comparator = comparator;
  }

  /** Benchmark every bundled simulation scenario. */
  public List<BenchmarkReport> runAll() {
    List<BenchmarkReport> reports = new ArrayList<>(SimulationScenarios.all().size());
    for (SimulationScenario scenario : SimulationScenarios.all()) {
      reports.add(run(scenario));
    }
    return reports;
  }

  /** Benchmark one scenario with the current tracking pipeline and no classification outputs. */
  public BenchmarkReport run(SimulationScenario scenario) {
    return run(scenario, Map.of());
  }

  /** Benchmark one scenario with optional per-source classification outputs. */
  public BenchmarkReport run(
      SimulationScenario scenario, Map<String, ClassificationPrediction> classificationPredictions) {
    Scenario truth = scenario.groundTruth();
    List<TrackingSnapshot> snapshots = runSnapshots(scenario);
    return comparator.compare(
        truth,
        new BenchmarkMeasurements(scenario.array(), snapshots, classificationPredictions));
  }

  private List<TrackingSnapshot> runSnapshots(SimulationScenario scenario) {
    SimulatedMicrophoneArraySource source = scenario.newSource();
    MicrophoneArray array = source.microphoneArray();
    TrackingPipeline pipeline = newPipeline(scenario);
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

  private TrackingPipeline newPipeline(SimulationScenario scenario) {
    MultiPeakDetector detector =
        new MultiPeakDetector(blockFrames, new FrequencyBand(150.0, 2_500.0), 3, 2.0);
    FrequencyClusterer clusterer = new FrequencyClusterer(25.0, 0.0, 2, 4);
    GccPhatTdoaEstimator tdoaEstimator = new GccPhatTdoaEstimator(343.0);
    DelayAndSumBeamformer beamformer = new DelayAndSumBeamformer(343.0);
    SourceTracker tracker = new SourceTracker(35.0, 4, 0.5, 0.04, 1.0, 1.0, 0.85, 0.4);
    FrameSchedule schedule = new FrameSchedule(scenario.sampleRate(), blockFrames, 0.8);
    return new TrackingPipeline(
        detector, clusterer, tdoaEstimator, beamformer, tracker, candidateGrid(scenario), schedule);
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
