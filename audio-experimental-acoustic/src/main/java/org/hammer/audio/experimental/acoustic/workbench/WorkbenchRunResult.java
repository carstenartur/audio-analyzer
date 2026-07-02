package org.hammer.audio.experimental.acoustic.workbench;

import java.util.List;
import java.util.Objects;
import org.hammer.audio.experimental.acoustic.benchmark.BenchmarkReport;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios.SimulationScenario;
import org.hammer.audio.experimental.acoustic.tracking.FrameSchedule;
import org.hammer.audio.experimental.acoustic.tracking.TrackedSource;
import org.hammer.audio.experimental.acoustic.tracking.TrackingSnapshot;

/**
 * Immutable result of a completed headless workbench scenario run.
 *
 * @param scenario the scenario that was executed
 * @param parameters the parameters used during the run
 * @param snapshots one {@link TrackingSnapshot} per processed audio block, in order
 * @param totalProcessingNanos cumulative pipeline wall-clock time across all blocks
 * @param benchmarkReport benchmark quality report comparing estimated tracks against ground truth,
 *     or {@code null} if benchmarking was not possible (e.g. empty run)
 * @param frameSchedule the real-time budget schedule used during the run, or {@code null} when not
 *     available; used to identify over-budget frames
 */
public record WorkbenchRunResult(
    SimulationScenario scenario,
    WorkbenchParameters parameters,
    List<TrackingSnapshot> snapshots,
    long totalProcessingNanos,
    BenchmarkReport benchmarkReport,
    FrameSchedule frameSchedule) {

  // Validates and defensively copies the snapshots list.
  public WorkbenchRunResult {
    Objects.requireNonNull(scenario, "scenario");
    Objects.requireNonNull(parameters, "parameters");
    Objects.requireNonNull(snapshots, "snapshots");
    if (totalProcessingNanos < 0L) {
      throw new IllegalArgumentException("totalProcessingNanos must be >= 0");
    }
    snapshots = List.copyOf(snapshots);
    // benchmarkReport and frameSchedule may be null
  }

  /**
   * Construct a result without a frame schedule (budget compliance is unavailable).
   *
   * @param scenario the scenario that was executed
   * @param parameters the parameters used during the run
   * @param snapshots one {@link TrackingSnapshot} per processed audio block, in order
   * @param totalProcessingNanos cumulative pipeline wall-clock time across all blocks
   * @param benchmarkReport benchmark quality report, or {@code null}
   */
  public WorkbenchRunResult(
      SimulationScenario scenario,
      WorkbenchParameters parameters,
      List<TrackingSnapshot> snapshots,
      long totalProcessingNanos,
      BenchmarkReport benchmarkReport) {
    this(scenario, parameters, snapshots, totalProcessingNanos, benchmarkReport, null);
  }

  /** Number of audio blocks that were processed. */
  public int blockCount() {
    return snapshots.size();
  }

  /** Whether at least one tracked source appeared in any frame. */
  public boolean anyTracked() {
    return snapshots.stream().anyMatch(s -> !s.tracks().isEmpty());
  }

  /** Maximum number of tracked sources observed in any single frame. */
  public int maxTracksInAnyFrame() {
    return snapshots.stream().mapToInt(s -> s.tracks().size()).max().orElse(0);
  }

  /**
   * Average pipeline processing time per block in nanoseconds, or {@code 0.0} if no blocks were
   * processed.
   */
  public double averageProcessingNanosPerBlock() {
    if (snapshots.isEmpty()) {
      return 0.0;
    }
    return (double) totalProcessingNanos / snapshots.size();
  }

  /**
   * Maximum pipeline processing time across all blocks in nanoseconds, or {@code 0} if no blocks
   * were processed.
   */
  public long maxProcessingNanosPerBlock() {
    return snapshots.stream().mapToLong(TrackingSnapshot::processingNanos).max().orElse(0L);
  }

  /**
   * Total number of distinct track IDs observed across all frames (may include transient tracks).
   */
  public long distinctTrackCount() {
    return snapshots.stream()
        .flatMap(s -> s.tracks().stream())
        .mapToInt(TrackedSource::id)
        .distinct()
        .count();
  }

  /**
   * Number of frames whose pipeline processing time exceeded the configured {@link FrameSchedule}
   * budget. Returns {@code 0} when no frame schedule is available.
   *
   * <p>A frame is considered over-budget when {@code snapshot.processingNanos() >
   * frameSchedule.maxProcessingNanos()}.
   *
   * @return count of over-budget frames, or 0 if no schedule was recorded
   */
  public long overBudgetFrameCount() {
    if (frameSchedule == null) {
      return 0L;
    }
    long budget = frameSchedule.maxProcessingNanos();
    return snapshots.stream().filter(s -> s.processingNanos() > budget).count();
  }

  /**
   * Whether the given snapshot's processing time exceeded the configured {@link FrameSchedule}
   * budget. Always returns {@code false} when no frame schedule is available.
   *
   * @param snapshot the snapshot to check; must not be {@code null}
   * @return {@code true} if {@code snapshot.processingNanos()} exceeds the per-block budget
   */
  public boolean isFrameOverBudget(TrackingSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    return frameSchedule != null && snapshot.processingNanos() > frameSchedule.maxProcessingNanos();
  }
}
