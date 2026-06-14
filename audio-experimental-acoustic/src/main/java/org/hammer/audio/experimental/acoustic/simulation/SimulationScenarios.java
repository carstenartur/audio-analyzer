package org.hammer.audio.experimental.acoustic.simulation;

import java.util.ArrayList;
import java.util.List;
import org.hammer.audio.acquisition.Microphone;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.experimental.acoustic.scenario.AcousticGroundTruth;
import org.hammer.audio.experimental.acoustic.scenario.ScenarioEnvironment;
import org.hammer.audio.experimental.acoustic.scenario.ScenarioSource;
import org.hammer.audio.experimental.acoustic.scenario.ScenarioTrajectory;
import org.hammer.audio.geometry.Vector2;

/**
 * Catalog of reproducible localization scenarios used by validation tests and demos.
 *
 * <p>Every scenario is a self-contained, deterministic {@link Scenario} record bundling a {@link
 * Room2D}, a {@link MicrophoneArray}, a list of {@link SoundEmitter2D}s, a sample rate, a duration
 * in seconds and a random seed. Two calls with identical scenario parameters produce bit-identical
 * signals via {@link SimulatedMicrophoneArraySource}.
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
 * </ul>
 */
public final class SimulationScenarios {

  private static final float SAMPLE_RATE = 16_000.0f;

  private SimulationScenarios() {
    // utility
  }

  /** One stationary 600 Hz tone at (1.5, 1.0) in an anechoic 3x2 m room. */
  public static Scenario singleSource() {
    return new Scenario(
        "single-source",
        new Room2D(3.0, 2.0, 0.0, 0.0),
        defaultArray(),
        List.of(new SoundEmitter2D(new Vector2(1.5, 1.0), Vector2.ZERO, 600.0, 0.5)),
        SAMPLE_RATE,
        0.5,
        1L);
  }

  /** Two stationary tones at 600 and 640 Hz, located at distinct positions in an anechoic room. */
  public static Scenario twoCloseFrequencies() {
    return new Scenario(
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
  public static Scenario noisyRoom() {
    return new Scenario(
        "noisy-room",
        new Room2D(3.0, 2.0, 0.0, 0.05),
        defaultArray(),
        List.of(new SoundEmitter2D(new Vector2(1.5, 1.2), Vector2.ZERO, 720.0, 0.5)),
        SAMPLE_RATE,
        0.5,
        3L);
  }

  /** One source travelling from (0.5, 1.0) to (2.5, 1.0) over the scenario duration. */
  public static Scenario movingSource() {
    return new Scenario(
        "moving-source",
        new Room2D(3.0, 2.0, 0.0, 0.0),
        defaultArray(),
        List.of(new SoundEmitter2D(new Vector2(0.5, 1.0), new Vector2(4.0, 0.0), 660.0, 0.5)),
        SAMPLE_RATE,
        0.5,
        4L);
  }

  /** One source moving primarily toward the array for Doppler validation. */
  public static Scenario movingTowardArray() {
    return new Scenario(
        "moving-toward-array",
        new Room2D(3.0, 2.0, 0.0, 0.0),
        defaultArray(),
        List.of(new SoundEmitter2D(new Vector2(1.5, 1.8), new Vector2(0.0, -2.0), 700.0, 0.5)),
        SAMPLE_RATE,
        0.5,
        6L);
  }

  /** One source moving laterally across the array. */
  public static Scenario movingAcrossArray() {
    return new Scenario(
        "moving-across-array",
        new Room2D(3.0, 2.0, 0.0, 0.0),
        defaultArray(),
        List.of(new SoundEmitter2D(new Vector2(0.6, 1.0), new Vector2(2.0, 0.0), 760.0, 0.5)),
        SAMPLE_RATE,
        0.5,
        7L);
  }

  /** Two moving sources with different frequencies and velocities. */
  public static Scenario twoMovingSources() {
    return new Scenario(
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

  /** Single source with reflective walls (specular x-axis reflection in the simulator). */
  public static Scenario reflectedEnvironment() {
    return new Scenario(
        "reflected-environment",
        new Room2D(3.0, 2.0, 0.35, 0.01),
        defaultArray(),
        List.of(new SoundEmitter2D(new Vector2(0.8, 1.0), Vector2.ZERO, 580.0, 0.5)),
        SAMPLE_RATE,
        0.5,
        5L);
  }

  /** All bundled scenarios in canonical order. */
  public static List<Scenario> all() {
    return List.of(
        singleSource(),
        twoCloseFrequencies(),
        noisyRoom(),
        movingSource(),
        movingTowardArray(),
        movingAcrossArray(),
        twoMovingSources(),
        reflectedEnvironment());
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

  /** One reproducible scenario. */
  public record Scenario(
      String name,
      Room2D room,
      MicrophoneArray array,
      List<SoundEmitter2D> emitters,
      float sampleRate,
      double durationSeconds,
      long randomSeed) {

    /** Validate and defensively copy emitters. */
    public Scenario {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("name must not be blank");
      }
      if (room == null || array == null) {
        throw new IllegalArgumentException("room and array must not be null");
      }
      if (emitters == null || emitters.isEmpty()) {
        throw new IllegalArgumentException("emitters must not be empty");
      }
      if (!(sampleRate > 0.0f)) {
        throw new IllegalArgumentException("sampleRate must be > 0");
      }
      if (!(durationSeconds > 0.0)) {
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
     * <p>Each {@link SoundEmitter2D} is mapped to a {@link ScenarioSource} with a linear {@link
     * ScenarioTrajectory} and an {@link AcousticGroundTruth} holding the emitter's fundamental
     * frequency.
     */
    public org.hammer.audio.experimental.acoustic.scenario.Scenario groundTruth() {
      List<ScenarioSource> sources = new ArrayList<>(emitters.size());
      for (int i = 0; i < emitters.size(); i++) {
        SoundEmitter2D emitter = emitters.get(i);
        ScenarioTrajectory trajectory =
            ScenarioTrajectory.linear(
                emitter.startMeters(), emitter.velocityMetersPerSecond(), durationSeconds, 2);
        AcousticGroundTruth acoustic = AcousticGroundTruth.ofFrequency(emitter.frequencyHz());
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
      return new org.hammer.audio.experimental.acoustic.scenario.Scenario(
          name, "Simulated scenario: " + name, sources, environment);
    }
  }
}
