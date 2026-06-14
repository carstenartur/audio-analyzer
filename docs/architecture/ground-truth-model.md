# Ground-truth scenario model

## Overview

The `org.hammer.audio.experimental.acoustic.scenario` package provides a formal ground-truth
representation for simulation, synthetic datasets and benchmark scenarios.

Evaluation algorithms for localization, tracking, feature extraction and classification all consume
the same shared model, enabling reproducible and comparable results across scenario types.

## Model structure

```
Scenario
 ├── id / description
 ├── sources: List<ScenarioSource>
 │     ├── sourceId / sourceType
 │     ├── trajectory: ScenarioTrajectory (optional)
 │     │     ├── timestamps
 │     │     ├── positions
 │     │     ├── velocities   (optional)
 │     │     └── orientations (optional)
 │     ├── acousticProperties: AcousticGroundTruth (optional)
 │     │     ├── fundamentalFrequencyHz
 │     │     ├── harmonics    (optional)
 │     │     ├── modulation   (optional)
 │     │     ├── jitter       (optional)
 │     │     ├── drift        (optional)
 │     │     └── signalPower  (optional)
 │     └── labels: ClassificationGroundTruth (optional)
 │           ├── species      (optional)
 │           ├── sex          (optional)
 │           ├── age          (optional)
 │           ├── feedingStatus (optional)
 │           └── labels: Map<String,String>
 └── environment: ScenarioEnvironment
       ├── speedOfSoundMetersPerSecond
       └── description
```

## Partial ground truth

Every optional field may be `null` (or an empty map for `labels`). This is intentional: real-world
datasets often know a source's species but not its exact trajectory, or they know the trajectory
but not the acoustic properties.

Code that consumes the model must check optional fields for `null` before using them:

```java
ScenarioSource source = scenario.sources().get(0);

if (source.acousticProperties() != null) {
    double f0 = source.acousticProperties().fundamentalFrequencyHz();
    // compare with estimated frequency
}

if (source.trajectory() != null) {
    Vector2 truePos = source.trajectory().positions().get(frameIndex);
    // compute localization error
}
```

## Usage examples

### Localization evaluation

```java
Scenario truth = SimulationScenarios.singleSource().groundTruth();
ScenarioSource src = truth.sources().get(0);
Vector2 truePosition = src.trajectory().positions().get(0);

Vector2 estimatedPosition = pipeline.estimatedPosition();
double errorMeters = truePosition.distanceTo(estimatedPosition);
```

### Tracking evaluation

```java
Scenario truth = SimulationScenarios.movingSource().groundTruth();
ScenarioTrajectory traj = truth.sources().get(0).trajectory();

for (int i = 0; i < traj.timestamps().size(); i++) {
    double t = traj.timestamps().get(i);
    Vector2 truePos = traj.positions().get(i);
    // compare against tracked position at time t
}
```

### Frequency / acoustic evaluation

```java
ScenarioSource source = scenario.sources().get(0);
AcousticGroundTruth acoustic = source.acousticProperties();
if (acoustic != null) {
    double trueFrequency = acoustic.fundamentalFrequencyHz();
    double estimatedFrequency = tracker.dominantFrequencyHz();
    double frequencyErrorHz = Math.abs(trueFrequency - estimatedFrequency);
}
```

### Classification evaluation

```java
ClassificationGroundTruth labels = source.labels();
if (labels != null && labels.species() != null) {
    String trueSpecies = labels.species();
    String predictedSpecies = classifier.predict();
    boolean correct = trueSpecies.equals(predictedSpecies);
}
```

## Synthetic scenario integration

`SimulationScenarios.SimulationScenario` exposes ground truth directly:

```java
SimulationScenarios.SimulationScenario sim = SimulationScenarios.movingSource();
Scenario truth = sim.groundTruth();
// truth.sources() contains one ScenarioSource with a linear trajectory
// and AcousticGroundTruth holding the emitter's fundamental frequency
```

## Design constraints

- **No UI coupling** — the model is in `audio-experimental-acoustic` and imports only
  `audio-geometry` primitives. It has no dependency on Swing or application code.
- **No dataset coupling** — the model is dataset-agnostic; adapters for specific datasets are
  separate concerns.
- **Partial truth support** — all non-essential fields are optional (`null`-safe).
- **Multiple sources** — `Scenario.sources()` is a list; scenarios with several simultaneous
  emitters are fully supported.
- **Immutability** — all records and lists are defensively copied; instances are safe to share
  across threads.

## Package location

```
audio-experimental-acoustic
  └── src/main/java/org/hammer/audio/experimental/acoustic/scenario/
        ├── Scenario.java
        ├── ScenarioSource.java
        ├── ScenarioTrajectory.java
        ├── AcousticGroundTruth.java
        ├── ClassificationGroundTruth.java
        ├── ScenarioEnvironment.java
        └── package-info.java
```

