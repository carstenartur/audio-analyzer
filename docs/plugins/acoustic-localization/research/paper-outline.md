# Low-Cost Acoustic Tracking of Weak Narrowband Sources Using TDOA and Doppler Fusion

> Working paper outline for the `audio-experimental-acoustic` subproject. Each
> section links to the concrete classes, tests and simulation scenarios that
> implement or validate it.

## Abstract

The `audio-experimental-acoustic` module is a modular low-cost acoustic tracking
research platform for weak narrowband moving sound sources. It combines:

- multi-microphone TDOA localization (cross-correlation and GCC-PHAT);
- Doppler-based radial-velocity estimation and per-microphone fusion;
- temporal frequency tracking with multi-peak detection and clustering;
- a 2D constant-velocity Kalman tracker with identity persistence.

The implementation is exposed as a `ServiceLoader`-discovered plugin
(`AcousticLocalizationPlugin`) on top of the stable `audio-plugin-api` and is
evaluated using deterministic simulation scenarios bundled in
`SimulationScenarios`. Experiments cover localization stability, velocity
estimation accuracy, robustness under noise and behaviour under reflections and
weak signals. The aim of these experiments is to test the hypothesis that
Doppler fusion may improve temporal tracking stability for moving narrowband
sources under controlled conditions; reported numerical results in this
document are placeholders pending a dedicated benchmark harness (see
[`experiments.md`](experiments.md)).

---

## 1. Introduction

### 1.1 Problem statement

Tracking weak moving sound sources with low-cost hardware is difficult because of
low signal-to-noise ratio, reflections, synchronization drift between cheap
capture devices and the poor spatial resolution of narrowband signals.

### 1.2 Motivation

Potential applications include bioacoustics (e.g. wingbeat tracking), robotics,
environmental sensing, educational DSP systems and low-cost research platforms
that need a reproducible end-to-end pipeline rather than a closed black box.

### 1.3 Contributions

1. A modular plugin-based acoustic tracking framework on top of
   `audio-plugin-api`.
2. Multi-microphone TDOA localization via `CrossCorrelationTdoaEstimator` and a
   PHAT-weighted frequency-domain implementation in `GccPhatTdoaEstimator`.
3. Doppler-based velocity estimation (`SimpleDopplerEstimator`,
   `ExactDopplerEstimator`) with multi-sensor fusion
   (`SimpleMultiSensorDopplerEstimator`) and geometry-aware reconstruction
   (`VelocityReconstructor`).
4. Frequency tracking for weak narrowband sources via `MultiPeakDetector` and
   `FrequencyClusterer`, embedded in the real-time `TrackingPipeline`.
5. Reproducible simulation scenarios in
   [`SimulationScenarios`](../../../../audio-experimental-acoustic/src/main/java/org/hammer/audio/experimental/acoustic/simulation/SimulationScenarios.java)
   and mirrored as JSON in
   [`simulation-datasets/`](simulation-datasets/) for external benchmarks.
6. A plugin-based DSP architecture enforced by architecture-boundary tests so
   experimental code cannot leak into the stable core modules.

---

## 2. Related work

### 2.1 Acoustic source localization

- Beamforming approaches — implemented at the simplest level by
  `DelayAndSumBeamformer`, which scores caller-supplied candidate grids.
- GCC-PHAT — implemented by the integer-sample `GccPhatTdoaEstimator` baseline and the selectable
  `SubSampleGccPhatTdoaEstimator`, with interpolated delays and peak-ambiguity diagnostics.
- Generic TDOA localization — covered by the `TdoaEstimator` interface and the
  cross-correlation reference implementation.

### 2.2 Bioacoustics

- Insect wingbeat analysis — motivates `WingbeatFrequencyTracker` and the
  narrowband simulator scenarios.
- Acoustic species classification — explicitly out of scope; see
  [plugin interpretation rules](../README.md#interpretation-rules).

### 2.3 Doppler tracking

- Radar-inspired velocity estimation — adapted to source motion in
  `ExactDopplerEstimator` using `f' = f · c / (c − vᵣ)`.
- Sonar localization techniques — inspire the per-microphone fusion approach in
  `SimpleMultiSensorDopplerEstimator`.

---

## 3. System architecture

### 3.1 Pipeline overview

```text
AudioBlock (synchronized multichannel frame)
  → MultiPeakDetector         (per-channel FFT + parabolic peak refinement)
  → FrequencyClusterer        (identity-aware grouping across channels)
  → TdoaEstimator             (cross-correlation or GCC-PHAT, all microphone pairs)
  → DelayAndSumBeamformer     (best position on a 2D candidate grid)
  → DopplerEstimator          (per-channel radial velocity)
  → MultiSensorDopplerEstimator + VelocityReconstructor
  → SourceTracker             (identity persistence + 2D constant-velocity Kalman)
  → TrackingSnapshot          (immutable per-frame output)
```

Detailed stage semantics live in [`../tracking.md`](../tracking.md).

### 3.2 Core modules

|         Maven module          |                                    Role                                    |
|-------------------------------|----------------------------------------------------------------------------|
| `audio-core`                  | Stable normalized audio blocks and format metadata.                        |
| `audio-geometry`              | Vector and microphone-array geometry types.                                |
| `audio-acquisition`           | Microphone, microphone array, multi-channel source contracts.              |
| `audio-dsp`                   | Generic block-to-block DSP composition and FFT utilities.                  |
| `audio-plugin-api`            | Plugin abstraction (`AudioAnalyzerPlugin`) discovered via `ServiceLoader`. |
| `audio-experimental-acoustic` | This subproject: TDOA, Doppler, frequency tracking, simulator.             |
| `audio-app`                   | Swing host that loads plugins through `PluginManager`.                     |

Architecture boundaries are enforced by `ArchitectureBoundaryTest` in the
repository: stable packages must not import `org.hammer.audio.experimental.*`.

---

## 4. Mathematical model

### 4.1 TDOA

For two microphones `i` and `j` observing the same source, the time difference of
arrival is:

```text
Δt_ij = (d_i − d_j) / c
```

where `d_i, d_j` are the Euclidean distances from the source to each microphone
and `c` is the speed of sound. `GccPhatTdoaEstimator` returns integer-sample delays;
`SubSampleGccPhatTdoaEstimator` is the implemented interpolated alternative available in the desktop
simulation workbench. The standalone microphone workflow currently uses the integer-sample default.

### 4.2 Doppler shift

Exact model for a stationary microphone and a source moving with radial velocity
`vᵣ` (positive when the source approaches):

```text
f' = f · c / (c − vᵣ)        →   vᵣ = c · (1 − f / f')
```

Implemented in `ExactDopplerEstimator#estimateRadialVelocity`. For small
velocities (`|vᵣ| ≪ c`) the linearized approximation:

```text
vᵣ ≈ c · (f' − f) / f
```

is used by `SimpleDopplerEstimator` and is sufficient for typical indoor
scenarios at <2 m/s.

### 4.3 Multi-sensor velocity fusion

Each microphone yields one radial-velocity estimate. `VelocityReconstructor`
combines at least two radial estimates and the array geometry into a 2D velocity
vector by solving a least-squares system on the unit direction vectors from each
microphone to the current source position.

### 4.4 Predictive tracking

For latency-sensitive consumers it may be useful to estimate where a source
*will be* a short time `Δ` into the future rather than where it was at the most
recent capture. The building blocks for this already exist inside the tracker:
`Kalman2D.predict(Δ)` advances the internal state by `Δ`, and
`TrackedSource.positionMeters()` / `velocityMetersPerSecond()` allow a
constant-velocity extrapolation by external callers. A dedicated prediction
surface, horizon-aware confidence reporting and a benchmark harness are not
yet implemented and are tracked as exploratory work in
[`predictive-tracking.md`](predictive-tracking.md) and
[`event-driven-sensing.md`](event-driven-sensing.md). The dependent metrics
*future-position prediction error*, *prediction drift over time*,
*trigger timing error* and *confidence decay vs prediction horizon* are
defined in [`evaluation-metrics.md`](evaluation-metrics.md).

---

## 5. Experimental setup

### 5.1 Room setup

All canonical experiments use a deterministic 2D virtual room defined by
[`Room2D`](../../../../audio-experimental-acoustic/src/main/java/org/hammer/audio/experimental/acoustic/simulation/Room2D.java):

|    Parameter     |    Default    |                      Notes                      |
|------------------|---------------|-------------------------------------------------|
| Width × height   | 3.0 m × 2.0 m | Single-room scale used by all scenarios.        |
| `reflectionGain` | 0.0–0.35      | 0 for anechoic, 0.35 in `reflectedEnvironment`. |
| `noiseAmplitude` | 0.0–0.05      | 0.05 in `noisyRoom`.                            |

### 5.2 Microphone array

Default 4-microphone planar array from `SimulationScenarios.defaultArray()`:

| Mic | Position (m) | Channel |
|-----|--------------|---------|
| m0  | (1.35, 0.0)  | 0       |
| m1  | (1.65, 0.0)  | 1       |
| m2  | (1.35, 0.3)  | 2       |
| m3  | (1.65, 0.3)  | 3       |

- Spacing: ≈30 cm in x, 30 cm in y.
- Sample rate: 16 kHz (matches `SimulationScenarios.SAMPLE_RATE`).
- Simulation synchronization: one shared `SampleClock`. A separate implemented calibration layer
  models measured channel offsets and affine drift; automatic multi-device clock recovery remains
  outside the live workflow.

### 5.3 Source configuration

Sources are 580–840 Hz tones with constant amplitude 0.45–0.5 and either zero or
constant 2D velocity, as defined per scenario. Frequencies live well above DC and
inside the comfortable resolution of the default FFT window.

---

## 6. Evaluation

Concrete formulas and units live in [`evaluation-metrics.md`](evaluation-metrics.md).
Headline metrics:

- **Localization error** — Euclidean distance between
  `TrackedSource#position` and the scenario emitter position.
- **Velocity error** — Euclidean norm between the reconstructed velocity vector
  and the emitter velocity.
- **Tracking stability** — temporal variance of `TrackedSource#position` over a
  scenario window.
- **Frequency stability** — temporal variance of the cluster frequency assigned
  to a tracked source.

---

## 7. Results (placeholders)

> The numbers below are slots to be filled in by the benchmark harness once it
> consumes [`simulation-datasets/`](simulation-datasets/). For now the
> deterministic scenarios are verified qualitatively by the unit tests under
> `audio-experimental-acoustic/src/test/java/...`.

### 7.1 Single source

- localization trajectory: `singleSource` — see `TrackingPipelineScenarioTest`.
- velocity reconstruction: not applicable (stationary).

### 7.2 Multi-source

- frequency separation: `twoCloseFrequencies` (40 Hz apart) and
  `twoMovingSources` (220 Hz apart).
- tracking continuity: `SourceTrackerTest` guards identity persistence.

### 7.3 Noise robustness

- localization degradation: `noisyRoom` (`noiseAmplitude = 0.05`).
- velocity estimation degradation: future work, requires moving source + noise.

---

## 8. Limitations

See the current [plugin interpretation rules](../README.md#interpretation-rules) and
[synchronization limits](../README.md#synchronization-limits):

- indoor reflections handled only with a simple specular model;
- live acquisition opens one multichannel device; no automatic independent-device clock recovery;
- integer-sample TDOA in the standalone workflow; the selectable sub-sample alternative still needs
  measured accuracy validation;
- sensitivity to low SNR, especially for sub-sample motion;
- narrowband ambiguity between sources with overlapping fundamentals.

---

## 9. Future work

- adaptive beamforming and stronger physical validation of the implemented sub-sample GCC-PHAT;
- ML-based species classification (explicitly out of scope today);
- outdoor tracking and 3D arrays;
- GPU acceleration of beamforming heatmaps;
- distributed/networked microphone arrays with calibrated clock recovery.

---

## 10. Conclusion

The subproject explores whether a modular DSP stack on commodity Java
infrastructure can produce meaningful tracking of weak narrowband moving sound
sources under controlled conditions. The combination of TDOA, Doppler estimation
and a 2D Kalman tracker is intended to improve temporal stability compared to
single-frame beamforming; a quantitative comparison is future work and depends
on the benchmark harness sketched in [`experiments.md`](experiments.md). Strict
module boundaries keep the experimental code from infecting the stable audio
core.
