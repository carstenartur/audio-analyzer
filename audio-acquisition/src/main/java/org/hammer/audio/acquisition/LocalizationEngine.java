package org.hammer.audio.acquisition;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * ServiceLoader boundary for a localization implementation. Hosts consume acquisition values, never
 * concrete plugin classes; implementations reuse their established processing pipeline.
 */
public interface LocalizationEngine {

  /** Algorithm/plugin identity recorded with every run. */
  String id();

  /** Version actually supplied by this implementation. */
  String version();

  /** Supported deterministic simulation names. */
  List<String> scenarios();

  /** Example geometry for a reusable starter profile. */
  MicrophoneArray exampleArray();

  /** Nominal sample rate for one deterministic simulator. */
  float simulationSampleRate(String scenario);

  /** Create simulated samples using the selected persisted array geometry. */
  MultiChannelAudioSource simulate(String scenario, MicrophoneArray array, int durationSeconds);

  /** Run any source through one implementation and close it deterministically. */
  Result run(
      MultiChannelAudioSource source,
      LocalizationExperiment experiment,
      Options options,
      Consumer<Frame> progress)
      throws IOException;

  /**
   * Bounded execution choices shared across source modes.
   *
   * @param scenario simulation/environment reference
   * @param blockFrames requested processing block size
   * @param durationSeconds maximum experiment duration
   */
  record Options(String scenario, int blockFrames, int durationSeconds) {
    // Immutable transport value.
  }

  /**
   * One localization position in metres.
   *
   * @param id track identity
   * @param x horizontal metres
   * @param y vertical metres
   * @param confidence tracker confidence
   */
  record Track(int id, double x, double y, double confidence) {
    // Immutable transport value.
  }

  /**
   * Typed progress without exposing plugin snapshot classes.
   *
   * @param index zero-based processed block index
   * @param processingNanos processing duration
   * @param synchronization actual calibration/timing assessment
   * @param reliableTdoa whether physical delay consistency passed
   * @param tracks latest estimated positions
   */
  record Frame(
      int index,
      long processingNanos,
      SynchronizationAssessment synchronization,
      boolean reliableTdoa,
      List<Track> tracks) {
    // Preserve immutable source evidence.
    public Frame {
      tracks = List.copyOf(tracks);
    }
  }

  /**
   * Final compute evidence with an honest benchmark-availability flag.
   *
   * @param experiment experiment/profile/calibration identity actually used
   * @param blocks processed blocks
   * @param overruns blocks exceeding the real-time budget
   * @param benchmarkAvailable whether reference ground truth permitted benchmarking
   * @param artifacts generated UTF-8 reports keyed by portable filename
   */
  record Result(
      LocalizationExperiment experiment,
      long blocks,
      long overruns,
      boolean benchmarkAvailable,
      Map<String, String> artifacts) {
    // Artifact content is fixed at completion.
    public Result {
      artifacts = Map.copyOf(artifacts);
    }
  }
}
