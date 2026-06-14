package org.hammer.audio.experimental.acoustic.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios;
import org.hammer.audio.geometry.Vector2;
import org.junit.jupiter.api.Test;

class ScenarioModelTest {

  // ---- AcousticGroundTruth ----

  @Test
  void acousticGroundTruthOfFrequencyHoldsFrequency() {
    AcousticGroundTruth truth = AcousticGroundTruth.ofFrequency(440.0);

    assertEquals(440.0, truth.fundamentalFrequencyHz());
    assertNull(truth.harmonicCount());
    assertNull(truth.harmonics());
    assertNull(truth.modulationFrequencyHz());
    assertNull(truth.modulationDepth());
    assertNull(truth.jitter());
    assertNull(truth.drift());
    assertNull(truth.noiseAmplitude());
    assertNull(truth.signalPower());
  }

  @Test
  void acousticGroundTruthAcceptsFullyPopulatedInstance() {
    AcousticGroundTruth truth =
        new AcousticGroundTruth(600.0, 2, List.of(1.0, 0.4), 5.0, 0.25, 0.01, 0.5, 0.02, -20.0);

    assertEquals(600.0, truth.fundamentalFrequencyHz());
    assertEquals(2, truth.harmonicCount());
    assertEquals(List.of(1.0, 0.4), truth.harmonics());
    assertEquals(5.0, truth.modulationFrequencyHz());
    assertEquals(0.25, truth.modulationDepth());
    assertEquals(0.01, truth.jitter());
    assertEquals(0.5, truth.drift());
    assertEquals(0.02, truth.noiseAmplitude());
    assertEquals(-20.0, truth.signalPower());
  }

  @Test
  void acousticGroundTruthRejectsInvalidFrequency() {
    assertThrows(IllegalArgumentException.class, () -> AcousticGroundTruth.ofFrequency(0.0));
    assertThrows(IllegalArgumentException.class, () -> AcousticGroundTruth.ofFrequency(-1.0));
    assertThrows(IllegalArgumentException.class, () -> AcousticGroundTruth.ofFrequency(Double.NaN));
  }

  @Test
  void acousticGroundTruthRejectsNegativeJitter() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AcousticGroundTruth(440.0, null, null, null, null, -0.01, null, null, null));
  }

  @Test
  void acousticGroundTruthHarmonicsAreDefensivelyCopied() {
    List<Double> harmonics = new java.util.ArrayList<>(List.of(2.0, 3.0));
    AcousticGroundTruth truth =
        new AcousticGroundTruth(440.0, 2, harmonics, null, null, null, null, null, null);
    harmonics.add(4.0);

    assertEquals(2, truth.harmonics().size());
  }

  // ---- ClassificationGroundTruth ----

  @Test
  void classificationGroundTruthOfSpeciesHoldsSpecies() {
    ClassificationGroundTruth truth = ClassificationGroundTruth.ofSpecies("Aedes aegypti");

    assertEquals("Aedes aegypti", truth.species());
    assertNull(truth.sex());
    assertNull(truth.age());
    assertNull(truth.feedingStatus());
    assertTrue(truth.labels().isEmpty());
  }

  @Test
  void classificationGroundTruthUnknownHasAllNulls() {
    ClassificationGroundTruth truth = ClassificationGroundTruth.unknown();

    assertNull(truth.species());
    assertNull(truth.sex());
    assertNull(truth.age());
    assertNull(truth.feedingStatus());
  }

  @Test
  void classificationGroundTruthLabelsAreDefensivelyCopied() {
    Map<String, String> labels = new java.util.HashMap<>(Map.of("key", "value"));
    ClassificationGroundTruth truth =
        new ClassificationGroundTruth("Anopheles gambiae", "female", "adult", "fed", labels);
    labels.put("extra", "data");

    assertEquals(1, truth.labels().size());
  }

  @Test
  void classificationGroundTruthRejectsNullLabels() {
    assertThrows(
        NullPointerException.class,
        () -> new ClassificationGroundTruth(null, null, null, null, null));
  }

  // ---- ScenarioTrajectory ----

  @Test
  void trajectoryLinearProducesCorrectEndPoints() {
    ScenarioTrajectory traj =
        ScenarioTrajectory.linear(new Vector2(0.0, 0.0), new Vector2(2.0, 0.0), 1.0, 3);

    assertEquals(3, traj.timestamps().size());
    assertEquals(0.0, traj.timestamps().get(0));
    assertEquals(1.0, traj.timestamps().get(2), 1e-9);
    assertEquals(new Vector2(0.0, 0.0), traj.positions().get(0));
    assertEquals(new Vector2(2.0, 0.0), traj.positions().get(2));
    assertNotNull(traj.velocities());
    assertEquals(new Vector2(2.0, 0.0), traj.velocities().get(0));
    assertNull(traj.orientations());
  }

  @Test
  void trajectoryLinearRejectsInvalidArguments() {
    Vector2 pos = Vector2.ZERO;
    Vector2 vel = Vector2.ZERO;
    assertThrows(
        IllegalArgumentException.class, () -> ScenarioTrajectory.linear(pos, vel, -1.0, 2));
    assertThrows(IllegalArgumentException.class, () -> ScenarioTrajectory.linear(pos, vel, 1.0, 1));
  }

  @Test
  void trajectorySizeMismatchIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ScenarioTrajectory(List.of(0.0, 1.0), List.of(Vector2.ZERO), null, null));
  }

  @Test
  void trajectoryRejectsNonFiniteOrNonMonotonicTimestamps() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ScenarioTrajectory(
                List.of(0.0, Double.NaN), List.of(Vector2.ZERO, Vector2.ZERO), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ScenarioTrajectory(
                List.of(0.0, 0.0), List.of(Vector2.ZERO, Vector2.ZERO), null, null));
  }

  @Test
  void trajectoryRejectsNonFiniteOrientations() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ScenarioTrajectory(
                List.of(0.0), List.of(Vector2.ZERO), null, List.of(Double.POSITIVE_INFINITY)));
  }

  @Test
  void trajectoryPartialTruthPermitsNullVelocities() {
    ScenarioTrajectory traj =
        new ScenarioTrajectory(List.of(0.0), List.of(Vector2.ZERO), null, null);

    assertNull(traj.velocities());
    assertNull(traj.orientations());
  }

  // ---- ScenarioSource ----

  @Test
  void scenarioSourceBuilderProducesFullyPopulatedSource() {
    ScenarioTrajectory traj =
        ScenarioTrajectory.linear(new Vector2(1.0, 1.0), Vector2.ZERO, 0.5, 2);
    AcousticGroundTruth acoustic = AcousticGroundTruth.ofFrequency(600.0);
    ClassificationGroundTruth classification = ClassificationGroundTruth.ofSpecies("Aedes aegypti");

    ScenarioSource source =
        ScenarioSource.builder("src-0", "mosquito")
            .trajectory(traj)
            .acousticProperties(acoustic)
            .labels(classification)
            .build();

    assertEquals("src-0", source.sourceId());
    assertEquals("mosquito", source.sourceType());
    assertNotNull(source.trajectory());
    assertEquals(600.0, source.acousticProperties().fundamentalFrequencyHz());
    assertEquals("Aedes aegypti", source.labels().species());
  }

  @Test
  void scenarioSourceAllowsNullOptionalFields() {
    ScenarioSource source = ScenarioSource.builder("src-1", "bird").build();

    assertNull(source.trajectory());
    assertNull(source.acousticProperties());
    assertNull(source.labels());
  }

  @Test
  void scenarioSourceRejectsBlankId() {
    assertThrows(
        IllegalArgumentException.class, () -> ScenarioSource.builder("", "mosquito").build());
  }

  // ---- ScenarioEnvironment ----

  @Test
  void scenarioEnvironmentDefaultIsValid() {
    ScenarioEnvironment env = ScenarioEnvironment.DEFAULT;

    assertTrue(env.speedOfSoundMetersPerSecond() > 0.0);
    assertFalse(env.description().isBlank());
  }

  @Test
  void scenarioEnvironmentRejectsNonPositiveSpeed() {
    assertThrows(IllegalArgumentException.class, () -> new ScenarioEnvironment(0.0, "test"));
    assertThrows(IllegalArgumentException.class, () -> new ScenarioEnvironment(-1.0, "test"));
  }

  // ---- Scenario ----

  @Test
  void scenarioHoldsAllFields() {
    ScenarioSource source = ScenarioSource.builder("src-0", "emitter").build();
    Scenario scenario =
        new Scenario("test-1", "Test scenario", List.of(source), ScenarioEnvironment.DEFAULT);

    assertEquals("test-1", scenario.id());
    assertEquals("Test scenario", scenario.description());
    assertEquals(1, scenario.sources().size());
    assertNotNull(scenario.environment());
  }

  @Test
  void scenarioRejectsEmptySources() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Scenario("test", "desc", List.of(), ScenarioEnvironment.DEFAULT));
  }

  @Test
  void scenarioSourceListIsDefensivelyCopied() {
    List<ScenarioSource> sources =
        new java.util.ArrayList<>(List.of(ScenarioSource.builder("src-0", "emitter").build()));
    Scenario scenario = new Scenario("s", "d", sources, ScenarioEnvironment.DEFAULT);
    sources.add(ScenarioSource.builder("src-1", "emitter").build());

    assertEquals(1, scenario.sources().size());
  }

  // ---- SimulationScenarios integration ----

  @Test
  void simulationScenarioGroundTruthHasCorrectSourceCount() {
    Scenario truth = SimulationScenarios.twoCloseFrequencies().groundTruth();

    assertEquals(2, truth.sources().size());
  }

  @Test
  void simulationScenarioGroundTruthFrequencyMatchesEmitter() {
    Scenario truth = SimulationScenarios.singleSource().groundTruth();

    assertEquals(600.0, truth.sources().get(0).acousticProperties().fundamentalFrequencyHz());
  }

  @Test
  void simulationScenarioGroundTruthTrajectoryHasStartPosition() {
    Scenario truth = SimulationScenarios.movingSource().groundTruth();
    ScenarioTrajectory traj = truth.sources().get(0).trajectory();

    assertNotNull(traj);
    assertFalse(traj.positions().isEmpty());
    assertEquals(new Vector2(0.5, 1.0), traj.positions().get(0));
  }

  @Test
  void simulationScenarioGroundTruthEnvironmentMatchesSimulator() {
    Scenario truth = SimulationScenarios.singleSource().groundTruth();

    assertEquals(343.0, truth.environment().speedOfSoundMetersPerSecond(), 1e-9);
  }

  @Test
  void allSimulationScenariosExposeGroundTruth() {
    for (SimulationScenarios.SimulationScenario scenario : SimulationScenarios.all()) {
      Scenario truth = scenario.groundTruth();
      assertNotNull(truth);
      assertFalse(truth.sources().isEmpty());
      assertEquals(scenario.emitters().size(), truth.sources().size());
    }
  }
}
