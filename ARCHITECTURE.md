# Audio Platform — Architecture

## Overview

This project has been refactored from a single-purpose Swing waveform demo into a layered
real-time audio processing platform. The current application separates audio acquisition,
buffering, DSP processing, analysis, localization, snapshots and visualization at both package and
Maven module boundaries. It includes microphone capture, deterministic demo input, FFT spectrum
analysis, stereo delay estimation, CSV/PNG evidence export and extension points for future modules
(spectrograms, noise diagnosis, triggered oscilloscopes, recording/replay, plug-in DSP, alternative
UIs, ...).

Stereo delay analysis estimates inter-channel delay and broad left/right direction from a stereo
pair. It does not provide full 3D localization or exact source coordinates.

## Layered architecture

```
┌──────────────────────────┐
│ audio source             │
│  microphone / demo input │
└────────────┬─────────────┘
             │ raw PCM bytes or generated AudioBlock
             ▼
┌──────────────────────────────────────────┐
│ capture / signal                         │
│  AudioCaptureService(Impl)               │
│    ├─ SampleDecoder  (bytes -> float[])  │
│    ├─ DemoAudioCaptureService            │
│    └─ SignalGenerator / demo presets     │
└────────────┬─────────────────────────────┘
             │ AudioBlock (immutable)
             ▼
┌──────────────────────────────────────────┐
│ buffer                                   │
│  AudioRingBuffer<AudioBlock>             │
│  (lock-free SPSC, bounded)               │
└────────────┬─────────────────────────────┘
             │ AudioBlock (consumer thread)
             ▼
┌──────────────────────────────────────────┐
│ dsp                                      │
│  DSPPipeline = [DSPProcessor, ...]       │
│  stateless, immutable                    │
└────────────┬─────────────────────────────┘
             │ AudioBlock
             ▼
┌──────────────────────────────────────────┐
│ analysis / localization                  │
│  AnalysisModule<S extends                │
│  AnalysisSnapshot>                       │
│    ├─ RmsPeakAnalyzer -> RmsPeakSnapshot │
│    ├─ SpectrumAnalyzer                   │
│    │  -> SpectrumSnapshot                │
│    └─ StereoDelayAnalyzer                │
│       -> StereoDelaySnapshot / Status    │
└────────────┬─────────────────────────────┘
             │ AnalysisSnapshot (immutable)
             ▼
┌──────────────────────────────────────────┐
│ snapshot                                 │
│  WaveformSnapshot                        │
│  PhaseScopeSnapshot                      │
│  (UI-friendly, audio-domain only)        │
└────────────┬─────────────────────────────┘
             │ snapshot (or block)
             ▼
┌──────────────────────────────────────────┐
│ ui                                       │
│  Swing panels and renderers              │
│  waveform, spectrum, phase, measurements │
│  export CSV / PNG                        │
└──────────────────────────────────────────┘
```

The key invariant: **everything below `ui` stays in normalized `float` audio space.** No
DSP/analysis/buffer/localization code knows about pixels, panel dimensions, Swing or JavaFX.

## Maven modules and dependency graph

The repository uses a root Maven parent with seven child modules. The structure keeps stable audio
APIs, plugin contracts, UI/application wiring and research-only localization code separated.

```text
audio-core
audio-geometry
audio-acquisition           -> audio-core, audio-geometry
audio-dsp                   -> audio-core
audio-plugin-api            (stable plugin contracts; no audio-* dependencies)
audio-experimental-acoustic -> audio-core, audio-geometry, audio-acquisition, audio-dsp,
                               audio-plugin-api
audio-app                   -> audio-core, audio-dsp, audio-plugin-api
                               runtime: audio-experimental-acoustic plugin
```

Boundary rules:

- `audio-core`, `audio-geometry`, `audio-acquisition` and `audio-dsp` are stable modules and must
  not depend on `audio-app` or `audio-experimental-acoustic`.
- `audio-plugin-api` contains only stable plugin contracts and must not depend on audio-domain,
  host or concrete-plugin modules.
- `audio-experimental-acoustic` is build-isolated from the app and depends only on stable modules
  plus `audio-plugin-api`.
- `audio-app` contains Swing UI, export code, JavaSound/demo wiring and the application entry
  point. It compiles only against `audio-plugin-api`; the concrete acoustic plugin is present as a
  runtime dependency so `ServiceLoader` can discover it.
- Tests live with the module that owns the production code they exercise. The app module keeps the
  cross-module `ArchitectureBoundaryTest` to verify both source imports and POM dependencies.

### Automated boundary enforcement

Two JUnit test classes in `audio-app` enforce these rules on every `mvn verify` run:

- **`ArchitectureBoundaryTest`** — source-level checks that scan every `.java` file in the four
  stable modules for forbidden import patterns (experimental package, Swing/AWT, app-layer
  packages) and verify that POM `<dependency>` declarations respect the allowed dependency graph.
  The nine test methods cover: stable modules never import experimental or UI code; app module
  never imports from concrete plugin packages; plugin API has no audio-domain imports; modules
  have no compile-scope dependency on forbidden peers.
- **`ArchitectureFitnessTest`** — ArchUnit 1.4.0 rules that scan compiled bytecode to ensure the
  Workflow bounded context (`org.hammer.audio.workflow..`) and Execution bounded context
  (`org.hammer.audio.workflow.execution..`) never depend on Swing/AWT or JGit, and that no
  cyclic package-slice dependencies exist across bounded contexts.

Both tests run in the `test` phase and fail the build on any violation.

See [`docs/architecture/bounded-contexts.md`](docs/architecture/bounded-contexts.md) for the
explicit bounded-context definitions including per-context package boundaries, dependency-direction
rules, public APIs and ownership annotations.

## Packages

|                 Package                  |            Module             |                                                                         Responsibility                                                                          |
|------------------------------------------|-------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `org.hammer.audio.core`                  | `audio-core`                  | Immutable audio-domain models: `AudioBlock`, `AudioFormatDescriptor`                                                                                            |
| `org.hammer.audio.buffer`                | `audio-core`                  | `AudioRingBuffer<T>` — bounded lock-free SPSC ring buffer                                                                                                       |
| `org.hammer.audio.snapshot`              | `audio-core`                  | UI-friendly immutable snapshots: `WaveformSnapshot`, `PhaseScopeSnapshot`                                                                                       |
| `org.hammer.audio.workflow`              | `audio-core`                  | Immutable workflow domain model: `Workflow`, `Node`, `Port`, `Edge`, `Metadata`; `WorkflowValidator`                                                            |
| `org.hammer.audio.workflow.execution`    | `audio-core`                  | Execution model: `ExecutionSnapshot`, `ExecutionPlan`, `ExecutionContext`, `ExecutionResult`, `ExecutionStatus`                                                 |
| `org.hammer.audio.geometry`              | `audio-geometry`              | Reusable 2D positions, rays and localization constraints                                                                                                        |
| `org.hammer.audio.acquisition`           | `audio-acquisition`           | API-neutral synchronized multichannel source, microphone metadata and sample clock APIs                                                                         |
| `org.hammer.audio.capture`               | `audio-dsp`                   | Sample decoding utilities (`SampleDecoder`)                                                                                                                     |
| `org.hammer.audio.dsp`                   | `audio-dsp`                   | `DSPProcessor` extension point + `DSPPipeline` composition                                                                                                      |
| `org.hammer.audio.analysis`              | `audio-dsp`                   | `AnalysisModule`, snapshots, `Fft`, `RmsPeakAnalyzer`, `SpectrumAnalyzer`, measurements                                                                         |
| `org.hammer.audio.localization`          | `audio-dsp`                   | Stereo delay estimation: `StereoDelayAnalyzer`, `StereoDelaySnapshot`, `StereoDelayStatus`                                                                      |
| `org.hammer.audio.signal`                | `audio-dsp`                   | Deterministic generators, including `DemoPresetGenerator` demo scenarios                                                                                        |
| `org.hammer.audio.diagnosis`             | `audio-dsp`                   | Reusable acoustic diagnostic analyzers and immutable findings                                                                                                   |
| `org.hammer.audio.spectrogram`           | `audio-dsp`                   | Spectrogram analyzer, frames and history                                                                                                                        |
| `org.hammer.audio.plugin`                | `audio-plugin-api`            | Stable plugin contracts: `AudioAnalyzerPlugin`, contribution interfaces (source, experiment, pipeline, snapshot, visualization, calibration, benchmark, export) |
| `org.hammer.audio.experimental.acoustic` | `audio-experimental-acoustic` | Isolated research plugin for wingbeat tracking, TDOA, beamforming and simulation                                                                                |
| `org.hammer.audio.ui`                    | `audio-app`                   | Render helpers and theme classes for pixel-aware UI code                                                                                                        |
| `org.hammer.audio.export`                | `audio-app`                   | CSV/PNG evidence export from app-facing snapshots and images                                                                                                    |
| `org.hammer.audio`                       | `audio-app` / `audio-dsp`     | Split package: capture service API, JavaSound/demo implementations and legacy `WaveformModel` in app; `DemoSignalType` in DSP for package stability             |
| `org.hammer`                             | `audio-app`                   | Swing application frame and panels                                                                                                                              |
| `org.hammer.audio.benchmark`             | `audio-dsp` JMH profile       | JMH benchmarks (ring buffer, FFT, signal generators)                                                                                                            |

## Key design choices

### 1. Immutable audio domain

`AudioFormatDescriptor` and `AudioBlock` are immutable, thread-safe and free of any UI or JavaSound
types. Samples are normalized `float[channels][frames]` in `[-1, 1]`. Each block carries a
monotonic `frameIndex` and a `timestampNanos` so any downstream consumer (analysis, recording,
replay) can correlate it back to the source.

### 2. Lock-free SPSC ring buffer

The audio capture thread is the sole producer; downstream DSP/analysis is the sole consumer.
`AudioRingBuffer` uses two `AtomicLong` sequences with `lazySet` semantics and a power-of-two mask,
avoiding locks on the hot path. The capacity is rounded up to the next power of two. Two write
strategies are exposed:

- `offer(T)` — fail fast if full (caller can decide what to do).
- `offerOverwrite(T)` — drop the oldest element if full (typical for "latest wins" UI feeds).

### 3. Composable DSP pipeline

`DSPProcessor` is a single-method functional interface (`AudioBlock -> AudioBlock`). Pipelines are
immutable lists of stages, threaded sequentially. Plug-in DSP modules implement `DSPProcessor` (or
`AnalysisModule` if they produce a snapshot rather than another block).

### 4. Pure-Java FFT and measurements

`Fft` is a dependency-free in-place radix-2 Cooley-Tukey FFT with cached twiddle and bit-reverse
tables. `SpectrumAnalyzer` produces immutable `SpectrumSnapshot` values for the Swing spectrum
panel and measurement/export paths. `MeasurementCalculator` combines block and spectrum data into
RMS, peak, clipping, dominant-frequency and spectrum-peak readouts.

### 5. Stereo delay estimation

`StereoDelayAnalyzer` computes normalized cross-correlation between the first two channels across
physically possible lags. It returns a `StereoDelaySnapshot` with delay in samples/milliseconds,
path-length difference, approximate angle, confidence and the correlation curve. `StereoDelayStatus`
classifies valid results and common rejection reasons: mono input, silence, low correlation or
physically impossible delay.

The angle is only a broad direction estimate from a two-microphone time-delay model. Reflections,
channel mismatch and microphone geometry can dominate the result; this is not full 3D localization.

### 6. Deterministic synthetic signals and demo presets

`SineGenerator`, `SquareGenerator` and `ChirpGenerator` produce repeatable `AudioBlock` streams with
no audio device. `DemoPresetGenerator` adds UI-oriented scenarios used by `DemoAudioCaptureService`:

- sine
- square
- chirp
- stereo delay test
- mosquito-like high-frequency burst
- moving chirp source
- 50 Hz hum + harmonics
- clipping test

These presets enable headless tests, repeatable demos and deterministic DSP/localization checks.

### 7. UI-only pixel scaling

`WaveformRenderer` is the single place that converts a `WaveformSnapshot` into pixel-space
arrays for a Swing canvas. Swing panels consume immutable audio-domain snapshots or
`AudioBlock` data and perform rendering/export at the application boundary.

For backwards compatibility the capture worker still builds a legacy `WaveformModel` via
`WaveformRenderer` so existing Swing panels keep working without changes; new consumers should
prefer `getRingBuffer()` / `getLatestBlock()` and call `WaveformRenderer` themselves at the UI
layer.

### 8. Workflow / execution separation

The workflow model (`org.hammer.audio.workflow`) describes *what* should be executed — it is
immutable and framework-independent. Execution-specific state lives exclusively in
`org.hammer.audio.workflow.execution` and is never mixed into the workflow model:

```
Workflow (immutable, design-time)
  │
  └─ ExecutionSnapshot.of(snapshotId, workflow, now)
       │  freezes node/edge lists at a point in time
       ▼
     ExecutionSnapshot (immutable)
       │
       └─ ExecutionPlan.of(planId, snapshot)
            │  derives topological node order (Kahn's algorithm)
            ▼
          ExecutionPlan (immutable, ordered node IDs)
            │
            └─ new ExecutionContext(executionId, plan, startedAt)
                 │  tracks per-node status (IDLE → QUEUED → RUNNING → terminal)
                 ▼
               ExecutionContext (mutable runtime state)
                 │
                 └─ context.toResult(completedAt)
                      ▼
                    ExecutionResult (immutable, per-node terminal statuses)
```

This separation enables:

- **Reproducible execution** — any number of independent executions can reference the same
  `ExecutionSnapshot`.
- **Concurrent editing** — users may continue editing the `Workflow` while a prior snapshot is
  being executed; the two are completely decoupled.
- **Simpler testing** — the workflow model is testable without any execution machinery.
- **Framework independence** — no UI, persistence or scheduling framework leaks into the domain.

### 9. Semantic workflow operations

Workflow edits are represented as explicit semantic operations in
`org.hammer.audio.workflow.WorkflowOperation` and executed through
`org.hammer.audio.workflow.WorkflowOperationLog`.

Supported operations are:

- `CreateNode`
- `DeleteNode`
- `MoveNode`
- `RenameNode`
- `ConnectPorts`
- `DisconnectPorts`
- `UpdateProperty`
- `GroupNodes`
- `UngroupNodes`

Each operation carries a stable operation id, timestamp, author, affected object ids, payload and
an inverse operation (when undo is possible). This keeps workflow changes deterministic and
enables operation logging, replay and undo without direct object mutation.

The same operation contract can later be reused for collaboration, semantic merge and Taxonomy or
other graph-based applications without coupling to a specific UI toolkit.

## Capture lifecycle

```
start()
   │
   ├─ open TargetDataLine or start demo worker
   ├─ allocate decode/generation buffers
   ├─ spawn worker thread (daemon, single-thread executor)
   │
 capture loop (worker):
   │  read raw bytes or generate demo block
   │  -> SampleDecoder.decode if using microphone input
   │  -> AudioBlock (frameIndex, timestamp)
   │  -> ringBuffer.offer(block), dropping the new block if full
   │  -> latestBlock = block (volatile, for "latest" consumers)
   │  -> latestModel = WaveformRenderer(snapshot, panelWidth, panelHeight)
   │
stop()
   │
   ├─ flag running=false
   ├─ shutdownNow worker
   └─ close TargetDataLine when live input is active
```

The legacy `WaveformModel` is still produced by the worker so existing Swing panels keep working.
New consumers should prefer `getRingBuffer()` or `getLatestBlock()`.

## Extension points

|                Want to add                |                                                                                    Implement                                                                                    |
|-------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| New DSP stage (filter, gain, ...)         | `DSPProcessor`, plug into a `DSPPipeline`                                                                                                                                       |
| New analyzer (loudness, correlation, ...) | `AnalysisModule<MySnapshot>` where `MySnapshot` implements `AnalysisSnapshot`                                                                                                   |
| New spectrum-derived view                 | Reuse `SpectrumAnalyzer` / `SpectrogramAnalyzer` output, add a UI renderer                                                                                                      |
| New diagnostic rule                       | Add a rule to `DiagnosisAnalyzer` returning a `DiagnosisFinding`                                                                                                                |
| New localization diagnostic               | Analyzer in `org.hammer.audio.localization` returning a snapshot                                                                                                                |
| New experimental localization algorithm   | New class under `org.hammer.audio.experimental.acoustic` (plugin module only)                                                                                                   |
| New visualization                         | Concrete snapshot class or `AudioBlock` input plus a UI renderer/panel                                                                                                          |
| New display-mode overlay (peak hold, avg) | New stateful helper alongside `PeakHoldSpectrum` / `SpectrumAverager`, surfaced through `SpectrumDisplayState`                                                                  |
| New trigger / time-domain alignment       | New class similar to `WaveformTrigger`, exposed via the relevant panel                                                                                                          |
| Alternative FFT backend                   | Replace `SpectrumAnalyzer`'s internal `Fft` with your own                                                                                                                       |
| Recording / replay                        | Reuse the `org.hammer.audio.recording` package (`AudioBlockRecordingWriter` / `AudioBlockRecordingReader` / `RecordedAudioCaptureService`) or write a new `AudioCaptureService` |
| A/B / regression comparison               | Build on `RecordingComparator` + `ComparisonReport`; add a renderer alongside `MarkdownComparisonReportRenderer`                                                                |
| Headless demo / test                      | Use `SignalGenerator` or `DemoPresetGenerator`                                                                                                                                  |
| **New workbench workflow (any domain)**   | Implement `AudioAnalyzerPlugin` and register via `ServiceLoader`; use the generic workbench contribution types below                                                            |

## Generic workbench / plugin API

`audio-plugin-api` provides a UI-independent, domain-neutral set of contribution interfaces that
any signal-processing or acoustic-measurement plugin can implement. The acoustic-localization
plugin is the first reference implementation; future resonance/density, distance/material and DSP
experiment workflows use the same infrastructure.

### Contribution types

|          Interface           |                                            Purpose                                            |
|------------------------------|-----------------------------------------------------------------------------------------------|
| `SignalSourceContribution`   | Advertises a signal source: microphone array, synthetic generator, recording, dataset replay  |
| `ExperimentContribution`     | Names a repeatable experiment or scenario (e.g. single-source sim, HumBugDB classification)   |
| `PipelineContribution`       | Describes a DSP + analysis stage chain with its ordered stages                                |
| `SnapshotStreamContribution` | Declares a per-frame result stream, including the snapshot type name for logging              |
| `VisualizationContribution`  | UI-independent description of a visual representation (render kind: 2d-spatial, time-series…) |
| `CalibrationContribution`    | Calibration procedure or persistent state (generator tuning, feature normalization…)          |
| `BenchmarkContribution`      | Quality metric or evaluation procedure with a unit (metres, percent, milliseconds…)           |
| `ExportFormatContribution`   | Serialization capability: Markdown report, CSV table, JSON-lines stream…                      |

All eight types are returned by default-empty methods on `AudioAnalyzerPlugin`. Plugins override
only the methods they need. The host uses the metadata for logging, display and routing without
depending on any concrete plugin type.

### How to plug in a new workflow domain

1. **Create a module** — follow the `audio-experimental-acoustic` pattern: depend only on
   `audio-core`, `audio-geometry`, `audio-acquisition`, `audio-dsp` and `audio-plugin-api`.

2. **Implement `AudioAnalyzerPlugin`** — override the contribution accessors relevant to the
   workflow:

   - `signalSourceContributions()` — describe how the domain acquires signal (real sensor,
     synthetic, imported file).
   - `experimentContributions()` — list named experimental scenarios.
   - `pipelineContributions()` — describe processing chains with ordered stage names.
   - `snapshotStreamContributions()` — declare result streams and their snapshot types.
   - `visualizationContributions()` — describe visual representations and their render kinds.
   - `calibrationContributions()` — describe calibration procedures.
   - `benchmarkContributions()` — declare quality metrics with units.
   - `exportFormatContributions()` — describe available export formats.
   - `viewContributions()` — add Swing panels (workbench, overview) using `ViewContribution`.
3. **Register via ServiceLoader** — add a
   `META-INF/services/org.hammer.audio.plugin.AudioAnalyzerPlugin` file pointing to your
   implementation. The host discovers it at runtime without any compile-time dependency.
4. **Keep the host clean** — `audio-app` must never import from concrete plugin packages. The
   `ArchitectureBoundaryTest` enforces this automatically.

### Example: future resonance/density workflow

```
audio-experimental-resonance  (new module)
  implements AudioAnalyzerPlugin:
    signalSourceContributions()    → SweepToneGenerator, RealMicrophoneArray
    experimentContributions()      → RoomModeMapping, MaterialDensityProbe
    pipelineContributions()        → FrequencyResponsePipeline
    snapshotStreamContributions()  → ResonanceSnapshot
    visualizationContributions()   → FrequencyResponseCurve (time-series), ModeMap (2d-spatial)
    calibrationContributions()     → ReferenceMicrophoneCalibration
    benchmarkContributions()       → ModeFrequencyError (Hz), DampingRatioError (dimensionless)
    exportFormatContributions()    → FrequencyResponseCSV, ImpulseResponseWAV
    viewContributions()            → ResonanceWorkbenchPanel (Swing)
  registered via META-INF/services → discovered at runtime, audio-app unchanged
```

## Experimental acoustic localization plugin

The acoustic localization work is intentionally isolated under
`org.hammer.audio.experimental.acoustic` and built in the `audio-experimental-acoustic` module.
Stable packages provide only reusable acquisition and geometry abstractions; mosquito-specific
frequency tracking, room simulation, GCC-PHAT/TDOA experiments and beamforming stay in the plugin.
Core code must not import `org.hammer.audio.experimental.*`. `ArchitectureBoundaryTest` enforces
that stable audio modules do not import experimental packages, do not depend on Swing/UI/app
packages, and do not declare POM dependencies on app, plugin-host or experimental modules. The
application host uses only `audio-plugin-api` at compile time; the concrete experimental plugin is a
runtime dependency and is loaded via Java `ServiceLoader`.

See [`docs/architecture/experimental-acoustic-localization.md`](docs/architecture/experimental-acoustic-localization.md)
and [`docs/plugins/acoustic-localization/README.md`](docs/plugins/acoustic-localization/README.md) for the
architecture review, coupling analysis, module-boundary rationale and current limitations.

## Experimental acoustic localization architecture

The `audio-experimental-acoustic` module implements a complete research platform for acoustic
source localization and wingbeat classification. It has evolved into a sophisticated pipeline
integrating dataset analysis, feature engineering, synthetic data generation and benchmarking.

### Research platform layers

```text
┌─────────────────────────────────────────────────┐
│ Dataset Import                                  │
│   HumBugDbImporter → DatasetManifest            │
└─────────────────┬───────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────┐
│ Feature Extraction                              │
│   WingbeatFeatureExtractor → FeatureVector      │
│   (dominant frequency, SNR, harmonic ratios)    │
└─────────────────┬───────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────┐
│ Feature Evaluation                              │
│   FeatureEvaluationService                      │
│   FeatureStatistics, FeatureHistogram           │
└─────────────────┬───────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────┐
│ Feature Ranking                                 │
│   FeatureRankingService                         │
│   Discriminative power analysis                 │
└─────────────────┬───────────────────────────────┘
                  │
      ┌───────────┴──────────────┐
      │                          │
      ▼                          ▼
┌──────────────────┐   ┌──────────────────────┐
│ Synthetic        │   │ Real Recordings      │
│ Generator        │   │ (HumBugDB)           │
│ (Simulation)     │   │                      │
└──────┬───────────┘   └──────┬───────────────┘
       │                      │
       └──────────┬───────────┘
                  ▼
┌─────────────────────────────────────────────────┐
│ Synthetic-vs-Real Comparison                    │
│   FeatureDistributionComparison                 │
└─────────────────┬───────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────┐
│ Generator Calibration                           │
│   GeneratorCalibrationService                   │
│   SyntheticParameterEstimator                   │
│   (tune generator to match real statistics)     │
└─────────────────┬───────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────┐
│ Benchmark Framework                             │
│   ├─ ClassificationAccuracyMetric               │
│   │   (accuracy, precision/recall, confusion)   │
│   └─ LocalizationErrorMetric                    │
│       (position, velocity, tracking continuity) │
└─────────────────┬───────────────────────────────┘
                  │
      ┌───────────┴────────────┐
      ▼                        ▼
┌──────────────────┐   ┌──────────────────────┐
│ Simulation       │   │ Imported Recording   │
│ Workbench        │   │ Workbench            │
│ (9 scenarios)    │   │ (HumBugDB analysis)  │
└──────────────────┘   └──────────────────────┘
```

### Core pipeline components

**Tracking Pipeline** (real-time localization):

```text
AudioBlock (multi-channel)
  → MultiPeakDetector (FFT + parabolic refinement)
  → FrequencyClusterer (cross-channel grouping)
  → TdoaEstimator (GCC-PHAT or cross-correlation)
  → DelayAndSumBeamformer (2D candidate grid scoring)
  → SourceTracker (Kalman smoothing + identity persistence)
  → TrackingSnapshot (immutable per-frame output)
```

**Classification Pipeline**:

```text
Dataset Recording (WAV + metadata)
  → WingbeatFeatureExtractor
  → WingbeatFeatureVector
  → RuleBasedWingbeatClassifier
  → ClassificationResult
  → Evaluation Metrics
```

### Package structure

The experimental module is organized into focused subpackages:

- `tracking` — Real-time source tracking with Kalman filtering and track persistence
- `simulation` — 2D room acoustics, moving emitters, reflections, Doppler
- `simulation.calibration` — Generator parameter estimation from real recordings
- `dataset` — Import, manifest, recording descriptors and audio loading
- `wingbeat` — Feature extraction, classification, evaluation workflows
- `feature.evaluation` — Feature statistics, histograms, distribution analysis
- `feature.ranking` — Discriminative power analysis for classifier development
- `feature.comparison` — Synthetic vs real feature distribution comparison
- `benchmark` — Localization and classification metrics, confusion matrices
- `workbench` — Interactive Swing panels for simulation and dataset analysis
- `visualization` — 2D room maps, track rendering (UI-independent models)
- `plugin` — Plugin descriptor and ServiceLoader integration

### Dual workbench architecture

The plugin contributes **two** interactive workbenches to the host application:

**1. Acoustic Localization Workbench (Simulation)**

- Nine deterministic scenarios (single source, moving source, reflections, noise, Doppler, etc.)
- Configurable pipeline parameters (FFT size, frequency band, TDOA estimator, grid resolution)
- Live per-frame logs with frequency clusters, track IDs, position, confidence
- 2D room visualization with microphones, ground truth and tracked positions
- Markdown/CSV/JSON export for offline analysis
- Headless runner (`WorkbenchScenarioRunner`) for programmatic use

**2. Imported Recording Workbench (Dataset Analysis)**

- Local offline HumBugDB import (user provides directory path)
- Recording browser with metadata, labels and durations
- Per-recording feature extraction and classification
- Dataset-level evaluation: accuracy, precision/recall, confusion matrix
- Feature distribution analysis across the corpus
- No automatic downloads (user must obtain dataset and accept its license)

### Benchmarking capabilities

**Localization metrics** (`LocalizationErrorMetric`, `FrequencyErrorMetric`, `DopplerErrorMetric`):
- Position error (mean, median, 95th percentile)
- Velocity error and Doppler accuracy
- Frequency stability over time
- Tracking continuity (identity persistence, track switching)
- Processing latency and real-time budget compliance

**Classification metrics** (`ClassificationAccuracyMetric`):
- Overall accuracy
- Per-label precision and recall
- Confusion matrix (ground truth vs predicted)
- Feature distribution statistics
- Baseline vs calibrated generator comparison

### Current implementation status

**Fully implemented:**
- End-to-end tracking pipeline with multi-peak detection, TDOA, beamforming, Kalman tracking
- HumBugDB dataset import and manifest generation
- Feature extraction (`WingbeatFeatureVector` with frequency, SNR, harmonics)
- Feature evaluation and distribution analysis
- Feature ranking for discriminative power
- Synthetic-vs-real comparison infrastructure
- Generator calibration framework (parameter estimation from real data)
- Rule-based classification baseline with evaluation metrics
- Benchmark framework for localization and classification
- Dual interactive workbenches with export capabilities
- Nine deterministic simulation scenarios
- Doppler velocity estimation and reconstruction

**Experimental / partial:**
- Generator calibration UI integration (infrastructure exists, workbench integration pending)
- Additional visualization contributions (heatmaps, confidence surfaces)

**Future research directions** (tracked in repository issues):
- Sub-sample GCC-PHAT peak interpolation
- Multi-source separation using probabilistic data association
- 3D geometry and calibrated array file formats
- Improved reflection models and room impulse responses
- Expanded benchmark corpus with more real recordings
- Synchronization and calibration framework for real hardware arrays
- Advanced localization algorithms beyond baseline GCC-PHAT

See the [Roadmap](ROADMAP.md#experimental-acoustic-localization) for detailed research directions
and [`docs/plugins/acoustic-localization/README.md`](docs/plugins/acoustic-localization/README.md)
for comprehensive usage instructions.

## Split package note: `org.hammer.audio`

`org.hammer.audio` is currently split across `audio-app` and `audio-dsp`: app-owned capture service
interfaces/implementations and `WaveformModel` live in `audio-app`, while `DemoSignalType` remains
in `audio-dsp` for source/package stability. This is acceptable on the current classpath-based
build, but it is a known risk for a future Java Platform Module System (JPMS) migration because JPMS
does not allow the same package to be exported by multiple named modules. The long-term direction is
to move shared enum/API types into a single stable package or module before adding `module-info.java`
files.

## Concurrency model

- Capture worker thread (single, daemon) — sole producer for the ring buffer.
- DSP / analysis threads — single consumer per ring buffer (SPSC).
- UI threads — read `latestBlock` / `latestModel` via volatile pointers; never mutate.
- Snapshots are immutable — safe to pass between threads without synchronization.

## Build, test, benchmark

```
./mvnw verify           # spotless, build, unit tests, JaCoCo check, static-analysis reports
./mvnw test             # unit tests only
./mvnw -Pjmh package    # JMH benchmarks (org.hammer.audio.benchmark.*)
```

See [`docs/MIGRATION.md`](docs/MIGRATION.md) for migration notes from the previous
`WaveformModel`-centric architecture.
