package org.hammer.audio.experimental.acoustic.simulation;

import java.util.ArrayList;
import java.util.List;
import org.hammer.audio.acquisition.Microphone;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.experimental.acoustic.scenario.AcousticGroundTruth;
import org.hammer.audio.experimental.acoustic.scenario.ClassificationGroundTruth;
import org.hammer.audio.experimental.acoustic.scenario.Scenario;
import org.hammer.audio.experimental.acoustic.scenario.ScenarioEnvironment;
import org.hammer.audio.experimental.acoustic.scenario.ScenarioSource;
import org.hammer.audio.experimental.acoustic.scenario.ScenarioTrajectory;
import org.hammer.audio.geometry.Vector2;

/**
 * Catalog of reproducible localization scenarios used by validation tests and demos.
 *
 * <p>Every scenario is a self-contained, deterministic {@link SimulationScenario} record bundling a
 * {@link Room2D}, a {@link MicrophoneArray}, a list of {@link SoundEmitter2D}s, a sample rate, a
 * duration in seconds and a random seed. Two calls with identical scenario parameters produce
 * bit-identical signals via {@link SimulatedMicrophoneArraySource}.
 *
 * <p>The provided scenarios mirror the canonical research situations:
 *
 * <ul>
 *   <li>{@link #singleSource()} — one stationary tonal source in an anechoic room.
 *   <li>{@link #twoCloseFrequencies()} — two stationary sources at different positions whose
 *       frequencies are close enough to challenge naive single-peak trackers.
 *   <li>{@link #noisyRoom()} — single source with significant background noise.
 *   <li>{@link #movingSource()} — one source travelling across the room with constant velocity.
 *   <li>{@link #movingAcrossArray()} — one source travelling laterally across the array.
 *   <li>{@link #twoMovingSources()} — two tones with distinct velocities.
 *   <li>{@link #reflectedEnvironment()} — single source with wall reflections enabled.
 *   <li>{@link #twoMosquitoWingbeats()} — two stationary deterministic wingbeat emitters at close
 *       mosquito frequencies, paired with matching benchmark metadata via {@link
 *       #twoMosquitoWingbeatsGroundTruth()}.
 * </ul>
 */
public final class SimulationScenarios {

  private static final float SAMPLE_RATE = 16_000.0f;

  private SimulationScenarios() {
    // utility
  }

  /** One stationary 600 Hz tone at (1.5, 1.0) in an anechoic 3x2 m room. */
  public static SimulationScenario singleSource() {
    return new SimulationScenario(
        "single-source",
        new Room2D(3.0, 2.0, 0.0, 0.0),
        defaultArray(),
        List.of(new SoundEmitter2D(new Vector2(1.5, 1.0), Vector2.ZERO, 600.0, 0.5)),
        SAMPLE_RATE,
        0.5,
        1L);
  }

  /** Two stationary tones at 600 and 640 Hz, located at distinct positions in an anechoic room. */
  public static SimulationScenario twoCloseFrequencies() {
    return new SimulationScenario(
        "two-close-frequencies",
        new Room2D(3.0, 2.0, 0.0, 0.0),
        defaultArray(),
        List.of(
            new SoundEmitter2D(new Vector2(1.0, 1.0), Vector2.ZERO, 600.0, 0.5),
            new SoundEmitter2D(new Vector2(2.0, 1.0), Vector2.ZERO, 640.0, 0.5)),
        SAMPLE_RATE,
        0.5,
        2L);
  }

  /** Single source plus broadband room noise; tests robustness of peak detection. */
  public static SimulationScenario noisyRoom() {
    return new SimulationScenario(
        "noisy-room",
        new Room2D(3.0, 2.0, 0.0, 0.05),
        defaultArray(),
        List.of(new SoundEmitter2D(new Vector2(1.5, 1.2), Vector2.ZERO, 720.0, 0.5)),
        SAMPLE_RATE,
        0.5,
        3L);
  }

  /** One source travelling from (0.5, 1.0) to (2.5, 1.0) over the scenario duration. */
  public static SimulationScenario movingSource() {
    return new SimulationScenario(
        "moving-source",
        new Room2D(3.0, 2.0, 0.0, 0.0),
        defaultArray(),
        List.of(new SoundEmitter2D(new Vector2(0.5, 1.0), new Vector2(4.0, 0.0), 660.0, 0.5)),
        SAMPLE_RATE,
        0.5,
        4L);
  }

  /** One source moving primarily toward the array for Doppler validation. */
  public static SimulationScenario movingTowardArray() {
    return new SimulationScenario(
        "moving-toward-array",
        new Room2D(3.0, 2.0, 0.0, 0.0),
        defaultArray(),
        List.of(new SoundEmitter2D(new Vector2(1.5, 1.8), new Vector2(0.0, -2.0), 700.0, 0.5)),
        SAMPLE_RATE,
        0.5,
        6L);
  }

  /** One source moving laterally across the array. */
  public static SimulationScenario movingAcrossArray() {
    return new SimulationScenario(
        "moving-across-array",
        new Room2D(3.0, 2.0, 0.0, 0.0),
        defaultArray(),
        List.of(new SoundEmitter2D(new Vector2(0.6, 1.0), new Vector2(2.0, 0.0), 760.0, 0.5)),
        SAMPLE_RATE,
        0.5,
        7L);
  }

  /** Two moving sources with different frequencies and velocities. */
  public static SimulationScenario twoMovingSources() {
    return new SimulationScenario(
        "two-moving-sources",
        new Room2D(3.0, 2.0, 0.0, 0.0),
        defaultArray(),
        List.of(
            new SoundEmitter2D(new Vector2(0.8, 1.0), new Vector2(1.4, 0.0), 620.0, 0.45),
            new SoundEmitter2D(new Vector2(2.2, 1.4), new Vector2(-0.8, -0.4), 840.0, 0.45)),
        SAMPLE_RATE,
        0.5,
        8L);
  }

  /**
   * Two stationary deterministic wingbeat sources at 600 Hz and 640 Hz.
   *
   * <p>Both sources are placed at distinct positions in an anechoic room. Their fundamentals are
   * intentionally close (40 Hz apart) to exercise the ability of narrow-band trackers to separate
   * overlapping tonal content, while the emitted waveforms reuse the mosquito-like harmonic, drift,
   * jitter and noise parameters exported through ground truth.
   */
  public static SimulationScenario twoMosquitoWingbeats() {
    List<WingbeatSignalParameters> params = mosquitoWingbeatParameters();
    return new SimulationScenario(
        "two-mosquito-wingbeats",
        new Room2D(3.0, 2.0, 0.0, 0.0),
        defaultArray(),
        List.of(
            new WingbeatEmitter2D(new Vector2(1.0, 1.0), Vector2.ZERO, 0.5, params.get(0), SAMPLE_RATE, 9L),
            new WingbeatEmitter2D(new Vector2(2.0, 1.0), Vector2.ZERO, 0.5, params.get(1), SAMPLE_RATE, 10L)),
        SAMPLE_RATE,
        0.5,
        9L);
  }

  /**
   * Build a rich benchmark ground-truth {@link Scenario} for the {@link #twoMosquitoWingbeats()}
   * scenario.
   *
   * <p>This richer ground-truth record is intended for benchmark comparison of frequency-extraction
   * and classification algorithms. Its acoustic metadata is derived from the same emitter
   * parameters used by {@link #twoMosquitoWingbeats()}.
   */
  public static Scenario twoMosquitoWingbeatsGroundTruth() {
    SimulationScenario scenario = twoMosquitoWingbeats();
    List<ScenarioSource> sources = new ArrayList<>(scenario.emitters().size());
    for (int i = 0; i < scenario.emitters().size(); i++) {
      AcousticEmitter2D emitter = scenario.emitters().get(i);
      ScenarioTrajectory trajectory =
          ScenarioTrajectory.linear(
              emitter.startMeters(),
              emitter.velocityMetersPerSecond(),
              scenario.durationSeconds(),
              2);
      AcousticGroundTruth acoustic = emitter.acousticGroundTruth();
      ClassificationGroundTruth labels = ClassificationGroundTruth.synthetic("synthetic-wingbeat");
      sources.add(
          ScenarioSource.builder("source-" + i, "mosquito")
              .trajectory(trajectory)
              .acousticProperties(acoustic)
              .labels(labels)
              .build());
    }
    ScenarioEnvironment environment =
        new ScenarioEnvironment(
            SimulatedMicrophoneArraySource.DEFAULT_SPEED_OF_SOUND_METERS_PER_SECOND,
            "Simulated air");
    return new Scenario(
        scenario.name(), "Simulated scenario: " + scenario.name(), sources, environment);
  }

  private static List<WingbeatSignalParameters> mosquitoWingbeatParameters() {
    return List.of(
        WingbeatSignalParameters.mosquitoLike(600.0), WingbeatSignalParameters.mosquitoLike(640.0));
  }

  /** Single source with reflective walls (specular x-axis reflection in the simulator). */
  public static SimulationScenario reflectedEnvironment() {
    return new SimulationScenario(
        "reflected-environment",
        new Room2D(3.0, 2.0, 0.35, 0.01),
        defaultArray(),
        List.of(new SoundEmitter2D(new Vector2(0.8, 1.0), Vector2.ZERO, 580.0, 0.5)),
        SAMPLE_RATE,
        0.5,
        5L);
  }

  /** All bundled scenarios in canonical order. */
  public static List<SimulationScenario> all() {
    return List.of(
        singleSource(),
        twoCloseFrequencies(),
        noisyRoom(),
        movingSource(),
        movingTowardArray(),
        movingAcrossArray(),
        twoMovingSources(),
        reflectedEnvironment(),
        twoMosquitoWingbeats());
  }

  /** Default 4-microphone square array spanning roughly 30 cm, centered near (1.5, 0.1). */
  public static MicrophoneArray defaultArray() {
    return new MicrophoneArray(
        List.of(
            new Microphone("m0", new Vector2(1.35, 0.0), 0),
            new Microphone("m1", new Vector2(1.65, 0.0), 1),
            new Microphone("m2", new Vector2(1.35, 0.3), 2),
            new Microphone("m3", new Vector2(1.65, 0.3), 3)));
  }

  /**
   * One reproducible simulation scenario.
   *
   * @param name scenario name
   * @param room room geometry and acoustic parameters
   * @param array microphone array definition
   * @param emitters emitters active in the scenario
   * @param sampleRate sample rate in Hz
   * @param durationSeconds simulation duration in seconds
   * @param randomSeed deterministic seed for generated noise
   */
  public record SimulationScenario(
      String name,
      Room2D room,
      MicrophoneArray array,
      List<AcousticEmitter2D> emitters,
      float sampleRate,
      double durationSeconds,
      long randomSeed) {

    /* Validate and defensively copy emitters. */
    public SimulationScenario {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("name must not be blank");
      }
      if (room == null || array == null) {
        throw new IllegalArgumentException("room and array must not be null");
      }
      if (emitters == null || emitters.isEmpty()) {
        throw new IllegalArgumentException("emitters must not be empty");
      }
      if (sampleRate <= 0.0f) {
        throw new IllegalArgumentException("sampleRate must be > 0");
      }
      if (durationSeconds <= 0.0) {
        throw new IllegalArgumentException("durationSeconds must be > 0");
      }
      emitters = List.copyOf(emitters);
    }

    /** Create a fresh deterministic audio source for this scenario. */
    public SimulatedMicrophoneArraySource newSource() {
      return new SimulatedMicrophoneArraySource(
          room, array, emitters, sampleRate, durationSeconds, randomSeed);
    }

    /**
     * Build the ground-truth {@link org.hammer.audio.experimental.acoustic.scenario.Scenario} for
     * this simulation scenario.
     *
     * <p>Each emitter is mapped to a {@link ScenarioSource} with a linear {@link
     * ScenarioTrajectory} and the emitter-provided {@link AcousticGroundTruth}.
     */
    public Scenario groundTruth() {
      List<ScenarioSource> sources = new ArrayList<>(emitters.size());
      for (int i = 0; i < emitters.size(); i++) {
        AcousticEmitter2D emitter = emitters.get(i);
        ScenarioTrajectory trajectory =
            ScenarioTrajectory.linear(
                emitter.startMeters(), emitter.velocityMetersPerSecond(), durationSeconds, 2);
        AcousticGroundTruth acoustic = emitter.acousticGroundTruth();
        sources.add(
            ScenarioSource.builder("source-" + i, "emitter")
                .trajectory(trajectory)
                .acousticProperties(acoustic)
                .build());
      }
      ScenarioEnvironment environment =
          new ScenarioEnvironment(
              SimulatedMicrophoneArraySource.DEFAULT_SPEED_OF_SOUND_METERS_PER_SECOND,
              "Simulated air");
      return new Scenario(name, "Simulated scenario: " + name, sources, environment);
    }
  }
}
