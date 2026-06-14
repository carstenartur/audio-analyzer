package org.hammer.audio.experimental.acoustic.workbench;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.experimental.acoustic.CrossCorrelationTdoaEstimator;
import org.hammer.audio.experimental.acoustic.DelayAndSumBeamformer;
import org.hammer.audio.experimental.acoustic.FrequencyBand;
import org.hammer.audio.experimental.acoustic.GccPhatTdoaEstimator;
import org.hammer.audio.experimental.acoustic.TdoaEstimator;
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

  /**
   * Run {@code scenario} with {@code parameters} and return the full result.
   *
   * @param scenario scenario to execute
   * @param parameters pipeline configuration
   * @return collected result containing all snapshots
   */
  public static WorkbenchRunResult run(
      SimulationScenario scenario, WorkbenchParameters parameters) {
    return run(scenario, parameters, null);
  }

  /**
   * Run {@code scenario} with {@code parameters}, notifying {@code callback} after each block.
   *
   * @param scenario scenario to execute
   * @param parameters pipeline configuration
   * @param callback optional per-block callback; {@code null} disables incremental notification
   * @return collected result containing all snapshots
   */
  public static WorkbenchRunResult run(
      SimulationScenario scenario, WorkbenchParameters parameters, ProgressCallback callback) {
    Objects.requireNonNull(scenario, "scenario");
    Objects.requireNonNull(parameters, "parameters");

    SimulatedMicrophoneArraySource source = scenario.newSource();
    MicrophoneArray array = source.microphoneArray();
    TrackingPipeline pipeline = buildPipeline(scenario, parameters);

    List<TrackingSnapshot> snapshots = new ArrayList<>();
    long totalProcessingNanos = 0L;
    int blockIndex = 0;

    while (true) {
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

    return new WorkbenchRunResult(scenario, parameters, snapshots, totalProcessingNanos);
  }

  /**
   * Construct a {@link TrackingPipeline} from the given scenario and workbench parameters.
   *
   * <p>Visible for testing.
   */
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
    FrameSchedule schedule = new FrameSchedule(scenario.sampleRate(), params.blockSize(), 0.8);
    return new TrackingPipeline(
        detector, clusterer, tdoaEstimator, beamformer, tracker, grid, schedule);
  }

  private static TdoaEstimator buildTdoaEstimator(WorkbenchParameters params) {
    double speedOfSound = SimulatedMicrophoneArraySource.DEFAULT_SPEED_OF_SOUND_METERS_PER_SECOND;
    return switch (params.tdoaEstimatorType()) {
      case CROSS_CORRELATION -> new CrossCorrelationTdoaEstimator(speedOfSound);
      case GCC_PHAT -> new GccPhatTdoaEstimator(speedOfSound);
    };
  }

  private static List<Vector2> buildCandidateGrid(SimulationScenario scenario, int steps) {
    List<Vector2> grid = new ArrayList<>((steps + 1) * (steps + 1));
    double width = scenario.room().widthMeters();
    double height = scenario.room().heightMeters();
    for (int xi = 0; xi <= steps; xi++) {
      for (int yi = 0; yi <= steps; yi++) {
        grid.add(new Vector2(width * xi / steps, height * yi / steps));
      }
    }
    return grid;
  }
}
