package org.hammer.audio.experimental.acoustic.workbench;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.hammer.audio.acquisition.LocalizationExperiment;
import org.hammer.audio.acquisition.LocalizationExperimentStage;
import org.hammer.audio.acquisition.LocalizationInputMode;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.acquisition.MicrophoneArrayLayout;
import org.hammer.audio.acquisition.MicrophoneArrayProfile;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.experimental.acoustic.CrossCorrelationTdoaEstimator;
import org.hammer.audio.experimental.acoustic.DelayAndSumBeamformer;
import org.hammer.audio.experimental.acoustic.FrequencyBand;
import org.hammer.audio.experimental.acoustic.GccPhatTdoaEstimator;
import org.hammer.audio.experimental.acoustic.SubSampleGccPhatTdoaEstimator;
import org.hammer.audio.experimental.acoustic.TdoaEstimator;
import org.hammer.audio.experimental.acoustic.benchmark.BenchmarkMeasurements;
import org.hammer.audio.experimental.acoustic.benchmark.BenchmarkReport;
import org.hammer.audio.experimental.acoustic.benchmark.TrackingBenchmarkComparator;
import org.hammer.audio.experimental.acoustic.scenario.Scenario;
import org.hammer.audio.experimental.acoustic.simulation.SimulatedMicrophoneArraySource;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios.SimulationScenario;
import org.hammer.audio.experimental.acoustic.tracking.FrameSchedule;
import org.hammer.audio.experimental.acoustic.tracking.FrequencyClusterer;
import org.hammer.audio.experimental.acoustic.tracking.MultiPeakDetector;
import org.hammer.audio.experimental.acoustic.tracking.SourceTracker;
import org.hammer.audio.experimental.acoustic.tracking.TrackingPipeline;
import org.hammer.audio.experimental.acoustic.tracking.TrackingSnapshot;
import org.hammer.audio.geometry.Vector2;

/**
 * Headless runner for acoustic localization workbench scenarios.
 *
 * <p>Runs a {@link SimulationScenario} block-by-block through a {@link TrackingPipeline} configured
 * from {@link WorkbenchParameters}, collects all {@link TrackingSnapshot}s and returns a {@link
 * WorkbenchRunResult}. This class contains no Swing or UI code and is safe to use in headless
 * tests.
 *
 * <p>An optional {@link ProgressCallback} receives each snapshot as it is produced, which lets
 * Swing panels update incrementally on a background thread without holding a reference to this
 * class.
 */
public final class WorkbenchScenarioRunner {

  private static final Logger LOGGER = Logger.getLogger(WorkbenchScenarioRunner.class.getName());

  /**
   * Maximum fraction of one block period that the pipeline may consume ({@value}). This value is
   * used to configure the {@link FrameSchedule} and is intentionally shared with the UI so both log
   * annotations and the result's budget compliance checks use the same threshold.
   */
  static final double PIPELINE_MAX_LOAD_FRACTION = 0.8;

  /** Called after each audio block is processed during an incremental run. */
  @FunctionalInterface
  public interface ProgressCallback {
    /**
     * Invoked after processing one block.
     *
     * @param snapshot the snapshot produced for the block just processed
     * @param blockIndex 0-based index of the block just processed
     */
    void onBlock(TrackingSnapshot snapshot, int blockIndex);
  }

  private WorkbenchScenarioRunner() {
    // utility class
  }

  /** Run {@code scenario} with {@code parameters} and return the full result. */
  public static WorkbenchRunResult run(
      SimulationScenario scenario, WorkbenchParameters parameters) {
    return run(scenario, parameters, null);
  }

  /** Run {@code scenario}, notifying {@code callback} after each block. */
  public static WorkbenchRunResult run(
      SimulationScenario scenario, WorkbenchParameters parameters, ProgressCallback callback) {
    Objects.requireNonNull(scenario, "scenario");
    Objects.requireNonNull(parameters, "parameters");

    try (SimulatedMicrophoneArraySource source = scenario.newSource()) {
      MicrophoneArray array = source.microphoneArray();
      TrackingPipeline pipeline = buildPipeline(scenario, parameters);

      List<TrackingSnapshot> snapshots = new ArrayList<>();
      long totalProcessingNanos = 0L;
      int blockIndex = 0;

      while (true) {
        if (Thread.currentThread().isInterrupted()) {
          break;
        }
        AudioBlock block = source.readBlock(parameters.blockSize()).orElse(null);
        if (block == null || block.frames() < parameters.blockSize()) {
          break;
        }
        TrackingSnapshot snapshot = pipeline.process(block, array);
        snapshots.add(snapshot);
        totalProcessingNanos += snapshot.processingNanos();
        if (callback != null) {
          callback.onBlock(snapshot, blockIndex);
        }
        blockIndex++;
      }

      return new WorkbenchRunResult(
          scenario,
          parameters,
          snapshots,
          totalProcessingNanos,
          computeBenchmarkReport(scenario, snapshots),
          pipeline.schedule(),
          simulationExperiment(scenario, parameters));
    } catch (java.io.IOException exception) {
      throw new IllegalStateException("Unexpected close failure on simulation source", exception);
    }
  }

  private static LocalizationExperiment simulationExperiment(
      SimulationScenario scenario, WorkbenchParameters parameters) {
    MicrophoneArrayProfile profile =
        new MicrophoneArrayProfile(
            "simulation." + scenario.name(),
            "Simulation array for " + scenario.name(),
            MicrophoneArrayLayout.CUSTOM,
            scenario.array(),
            Set.of(LocalizationInputMode.SIMULATION),
            null,
            null);
    return new LocalizationExperiment(
        "simulation." + scenario.name() + '.' + scenario.randomSeed(),
        "Simulation: " + scenario.name(),
        profile,
        LocalizationInputMode.SIMULATION,
        "scenario:" + scenario.name(),
        Instant.EPOCH,
        LocalizationExperimentStage.LOCALIZED,
        Map.of(
            "blockSize", Integer.toString(parameters.blockSize()),
            "randomSeed", Long.toString(scenario.randomSeed()),
            "sampleRate", Float.toString(scenario.sampleRate())));
  }

  /** Compute a benchmark report, or {@code null} when comparison is unavailable. */
  private static BenchmarkReport computeBenchmarkReport(
      SimulationScenario scenario, List<TrackingSnapshot> snapshots) {
    if (snapshots.isEmpty()) {
      return null;
    }
    try {
      Scenario truth = scenario.groundTruth();
      BenchmarkMeasurements measurements = BenchmarkMeasurements.of(scenario.array(), snapshots);
      return new TrackingBenchmarkComparator().compare(truth, measurements);
    } catch (RuntimeException exception) {
      LOGGER.log(Level.WARNING, "Benchmark report computation failed", exception);
      return null;
    }
  }

  /** Construct a {@link TrackingPipeline} from the given scenario and workbench parameters. */
  static TrackingPipeline buildPipeline(SimulationScenario scenario, WorkbenchParameters params) {
    FrequencyBand band = new FrequencyBand(params.bandMinHz(), params.bandMaxHz());
    MultiPeakDetector detector =
        new MultiPeakDetector(params.fftSize(), band, params.maxPeaks(), params.minSnr());
    FrequencyClusterer clusterer =
        new FrequencyClusterer(params.clusteringToleranceHz(), 0.0, 2, 4);
    TdoaEstimator tdoaEstimator = buildTdoaEstimator(params);
    DelayAndSumBeamformer beamformer =
        new DelayAndSumBeamformer(
            SimulatedMicrophoneArraySource.DEFAULT_SPEED_OF_SOUND_METERS_PER_SECOND);
    SourceTracker tracker =
        new SourceTracker(
            params.trackerFrequencyMatchHz(),
            params.trackerMissingFramesToDrop(),
            0.5,
            0.04,
            1.0,
            1.0,
            params.trackerConfidenceDecay(),
            params.trackerConfidenceGain());
    List<Vector2> grid = buildCandidateGrid(scenario, params.candidateGridSteps());
    FrameSchedule schedule =
        new FrameSchedule(scenario.sampleRate(), params.blockSize(), PIPELINE_MAX_LOAD_FRACTION);
    return new TrackingPipeline(
        detector, clusterer, tdoaEstimator, beamformer, tracker, grid, schedule);
  }

  /** Creates the configured interchangeable TDOA stage. Visible for tests. */
  static TdoaEstimator buildTdoaEstimator(WorkbenchParameters params) {
    double speedOfSound = SimulatedMicrophoneArraySource.DEFAULT_SPEED_OF_SOUND_METERS_PER_SECOND;
    return switch (params.tdoaEstimatorType()) {
      case CROSS_CORRELATION -> new CrossCorrelationTdoaEstimator(speedOfSound);
      case GCC_PHAT -> new GccPhatTdoaEstimator(speedOfSound);
      case SUB_SAMPLE_GCC_PHAT -> new SubSampleGccPhatTdoaEstimator(speedOfSound);
    };
  }

  private static List<Vector2> buildCandidateGrid(SimulationScenario scenario, int steps) {
    if (steps <= 0) {
      throw new IllegalArgumentException("candidateGridSteps must be positive, got " + steps);
    }
    List<Vector2> grid = new ArrayList<>((steps + 1) * (steps + 1));
    double width = scenario.room().widthMeters();
    double height = scenario.room().heightMeters();
    for (int xIndex = 0; xIndex <= steps; xIndex++) {
      for (int yIndex = 0; yIndex <= steps; yIndex++) {
        grid.add(new Vector2(width * xIndex / steps, height * yIndex / steps));
      }
    }
    return grid;
  }
}
