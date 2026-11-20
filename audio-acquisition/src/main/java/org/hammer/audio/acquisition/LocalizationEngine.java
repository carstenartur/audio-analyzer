package org.hammer.audio.acquisition;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
   * @param processingBudgetNanos actual processing deadline used by the implementation
   * @param synchronization actual calibration/timing assessment
   * @param reliableTdoa whether physical delay consistency passed
   * @param tracks latest estimated positions
   */
  record Frame(
      int index,
      long processingNanos,
      long processingBudgetNanos,
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
   * <p>Declare required reports independently of the reports successfully generated. Hosts use this
   * declaration to distinguish complete evidence from missing output; an empty declaration cannot
   * establish complete standalone localization evidence. Each declared name must be a portable
   * filename. Providers may choose their own report names and formats.
   *
   * @param experiment experiment/profile/calibration identity actually used
   * @param blocks processed blocks
   * @param overruns blocks exceeding the real-time budget
   * @param benchmarkAvailable whether reference ground truth permitted benchmarking
   * @param requiredArtifacts reports required for complete evidence, independent of generated keys
   * @param artifacts generated UTF-8 reports keyed by portable filename
   */
  record Result(
      LocalizationExperiment experiment,
      long blocks,
      long overruns,
      boolean benchmarkAvailable,
      Set<String> requiredArtifacts,
      Map<String, String> artifacts) {
    // Preserve the expected-output contract even when a provider omits a report or later mutates
    // inputs.
    public Result {
      requiredArtifacts = Set.copyOf(requiredArtifacts);
      artifacts = Map.copyOf(artifacts);
    }
  }
}
