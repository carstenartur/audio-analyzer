package org.hammer.audio.experimental.acoustic.benchmark.localization;

import java.util.Objects;
import org.hammer.audio.experimental.acoustic.benchmark.BenchmarkReport;
import org.hammer.audio.experimental.acoustic.benchmark.TrackingScenarioBenchmarkRunner;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios.SimulationScenario;

/**
 * {@link LocalizationBenchmark} implementation using TDOA-based localization via the existing
 * {@link TrackingScenarioBenchmarkRunner} pipeline (GCC-PHAT TDOA + delay-and-sum beamforming +
 * Kalman tracking).
 *
 * <p>This benchmark delegates all signal processing to the default tracking pipeline and extracts
 * the relevant localization metrics from the resulting {@link BenchmarkReport}.
 */
public final class TdoaLocalizationBenchmark implements LocalizationBenchmark {

  private static final int DEFAULT_BLOCK_FRAMES = 1024;

  private final TrackingScenarioBenchmarkRunner runner;

  /** Create a benchmark with the default block size ({@value DEFAULT_BLOCK_FRAMES} frames). */
  public TdoaLocalizationBenchmark() {
    this(DEFAULT_BLOCK_FRAMES);
  }

  /**
   * Create a benchmark with the given block size.
   *
   * @param blockFrames FFT block size in samples; must be {@code >= 128}
   */
  public TdoaLocalizationBenchmark(int blockFrames) {
    this.runner = new TrackingScenarioBenchmarkRunner(blockFrames);
  }

  @Override
  public LocalizationBenchmarkResult run(SimulationScenario scenario) {
    Objects.requireNonNull(scenario, "scenario");
    BenchmarkReport report = runner.run(scenario);
    return extractResult(scenario.name(), report);
  }

  @Override
  public String name() {
    return "TdoaLocalizationBenchmark";
  }

  /** Extract a {@link LocalizationBenchmarkResult} from a {@link BenchmarkReport}. */
  private static LocalizationBenchmarkResult extractResult(
      String scenarioId, BenchmarkReport report) {

    double meanLocError =
        report.localization().meanDistanceErrorMeters() != null
            ? report.localization().meanDistanceErrorMeters()
            : 0.0;

    // trackingError: 1 - continuity, or 0 if unavailable
    double trackingError = 0.0;
    if (report.trackContinuity() != null) {
      trackingError = 1.0 - report.trackContinuity();
    }

    // False positives and negatives: derived from rates × total evaluated frames
    int evaluatedFrames = report.snapshotCount();
    int falsePositives = 0;
    int falseNegatives = 0;
    if (report.falsePositiveRate() != null && evaluatedFrames > 0) {
      falsePositives = (int) Math.round(report.falsePositiveRate() * evaluatedFrames);
    }
    if (report.falseNegativeRate() != null && evaluatedFrames > 0) {
      falseNegatives = (int) Math.round(report.falseNegativeRate() * evaluatedFrames);
    }

    return new LocalizationBenchmarkResult(
        scenarioId,
        meanLocError,
        Math.min(1.0, Math.max(0.0, trackingError)),
        falsePositives,
        falseNegatives,
        evaluatedFrames);
  }
}
