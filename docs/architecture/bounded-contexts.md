# Bounded Contexts

This document defines the explicit bounded contexts of the Audio Analyzer platform, their package
and module boundaries, allowed dependency directions, public APIs and ownership.

Each bounded context has a single responsibility. Cross-context communication happens only through
stable interfaces listed under *Public API* for each context. Cyclic dependencies between contexts
are prohibited.

## Context overview

Dependency direction: `A ◄── B` means B may import from A. The reverse is forbidden.

```
  Foundation layer (no project-internal imports):
    Audio Processing            Workflow
          ◄── Persistence           ◄── Execution
          ◄── Benchmarking          ◄── Validation
          ◄── Visualization         ◄── Benchmarking
                                    ◄── Visualization
                                    ◄── Collaboration (future)

  Persistence ◄── Visualization
  Persistence ◄── Collaboration (future)
  Execution   ◄── Visualization
  Validation  ◄── Visualization

  Visualization: terminal context — nothing may import from it.
```

| Bounded Context  |                        Module(s)                         |                                                                                                                                                                     Root package(s)                                                                                                                                                                     |                    Status                    |
|------------------|----------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------|
| Audio Processing | audio-core, audio-geometry, audio-acquisition, audio-dsp | `org.hammer.audio.core`, `org.hammer.audio.buffer`, `org.hammer.audio.snapshot`, `org.hammer.audio.geometry`, `org.hammer.audio.acquisition`, `org.hammer.audio.capture`, `org.hammer.audio.dsp`, `org.hammer.audio.analysis`, `org.hammer.audio.localization`, `org.hammer.audio.signal`, `org.hammer.audio.diagnosis`, `org.hammer.audio.spectrogram` | Stable                                       |
| Workflow         | audio-core                                               | `org.hammer.audio.workflow`                                                                                                                                                                                                                                                                                                                             | Stable                                       |
| Execution        | audio-core                                               | `org.hammer.audio.workflow.execution`                                                                                                                                                                                                                                                                                                                   | Stable                                       |
| Validation       | audio-core                                               | `org.hammer.audio.workflow` (`WorkflowValidator`)                                                                                                                                                                                                                                                                                                       | Stable                                       |
| Persistence      | audio-dsp                                                | `org.hammer.audio.recording`                                                                                                                                                                                                                                                                                                                            | Stable (audio); Workflow persistence: future |
| Visualization    | audio-app, audio-experimental-acoustic                   | `org.hammer.audio.ui`, `org.hammer`, `org.hammer.audio.experimental.acoustic.visualization`, `org.hammer.audio.experimental.acoustic.workbench`                                                                                                                                                                                                         | App-layer                                    |
| Benchmarking     | audio-dsp (JMH), audio-experimental-acoustic             | `org.hammer.audio.benchmark`, `org.hammer.audio.experimental.acoustic.benchmark`                                                                                                                                                                                                                                                                        | Stable (JMH); Experimental (acoustic)        |
| Collaboration    | future                                                   | future                                                                                                                                                                                                                                                                                                                                                  | Future                                       |

---

## 1. Audio Processing

**Responsibility**: Core audio domain types and the signal-processing pipeline. Provides the
foundational building blocks — immutable audio blocks, ring buffers, DSP processors, analyzers,
signal generators and geometry — that all other contexts build on.

**Modules**: `audio-core`, `audio-geometry`, `audio-acquisition`, `audio-dsp`

**Package roots**:

|             Package             |                              Responsibility                               |
|---------------------------------|---------------------------------------------------------------------------|
| `org.hammer.audio.core`         | Immutable audio-domain models: `AudioBlock`, `AudioFormatDescriptor`      |
| `org.hammer.audio.buffer`       | `AudioRingBuffer<T>` — lock-free SPSC ring buffer                         |
| `org.hammer.audio.snapshot`     | UI-friendly immutable snapshots: `WaveformSnapshot`, `PhaseScopeSnapshot` |
| `org.hammer.audio.geometry`     | 2D positions, rays and localization constraints                           |
| `org.hammer.audio.acquisition`  | Multichannel source, microphone metadata, sample clock APIs               |
| `org.hammer.audio.capture`      | `SampleDecoder` — byte-to-float PCM decoding                              |
| `org.hammer.audio.dsp`          | `DSPProcessor`, `DSPPipeline` — composable stateless DSP stages           |
| `org.hammer.audio.analysis`     | `AnalysisModule`, `Fft`, `RmsPeakAnalyzer`, `SpectrumAnalyzer`            |
| `org.hammer.audio.localization` | Stereo delay estimation: `StereoDelayAnalyzer`, `StereoDelaySnapshot`     |
| `org.hammer.audio.signal`       | Deterministic generators and demo presets                                 |
| `org.hammer.audio.diagnosis`    | Reusable acoustic diagnostic analyzers and immutable findings             |
| `org.hammer.audio.spectrogram`  | Spectrogram analyzer, frames and rolling history                          |

**Public API** (stable interfaces for cross-context use):
- `AudioBlock`, `AudioFormatDescriptor`
- `AudioRingBuffer<T>`
- `WaveformSnapshot`, `PhaseScopeSnapshot`
- `DSPProcessor`, `DSPPipeline`
- `AnalysisModule<S>`, `AnalysisSnapshot`
- `Fft`
- `SignalGenerator`
- `SampleDecoder`

**Allowed dependencies**: None within this project. Depends only on Java SE.

**Dependency rules**:
- Must not import from `org.hammer.audio.workflow.*`
- Must not import from `org.hammer.audio.workflow.execution.*`
- Must not import from `org.hammer.audio.ui.*`
- Must not import from `org.hammer.*` (Swing panels)
- Must not import from `org.hammer.audio.experimental.*`
- Must not import from `org.hammer.audio.plugin.*`

**Ownership**: Core platform team.

---

## 2. Workflow

**Responsibility**: Immutable, framework-independent domain model for workflow graphs. Describes
*what* should be executed — nodes, ports, edges, metadata and typed data flows — without containing
any execution state, persistence concerns or UI code.

**Module**: `audio-core`

**Package root**: `org.hammer.audio.workflow`

**Key types**:
- `Workflow` — immutable top-level graph
- `Node` — typed executable or configuring unit
- `Port` — typed, directional connection point
- `Edge` — connects two compatible ports
- `Metadata` — name, description and creation timestamp
- `DataType`, `DataTypes`, `TypeRegistry` — port type system
- `PortDirection`, `PortMultiplicity`
- `StableIds` — deterministic ID generation
- `WorkflowOperation`, `WorkflowOperationLog` — semantic edit operations for undo/redo and audit

**Public API**: All types listed above.

**Allowed dependencies**: Java SE only.

**Dependency rules**:
- Must not import from `org.hammer.audio.workflow.execution.*` (Execution context)
- Must not import from `org.hammer.audio.core.*`, `org.hammer.audio.buffer.*`, etc. (Audio Processing)
- Must not import from `org.hammer.audio.ui.*` or `org.hammer.*` (Visualization)

**Ownership**: Workflow domain team.

---

## 3. Execution

**Responsibility**: Runtime execution model derived from a frozen workflow snapshot. Manages the
lifecycle of a single workflow run: snapshot creation, topological ordering, per-node status
transitions and immutable result capture.

**Module**: `audio-core`

**Package root**: `org.hammer.audio.workflow.execution`

**Key types**:
- `ExecutionSnapshot` — immutable freeze of a workflow at execution start time
- `ExecutionPlan` — topologically ordered node list derived from a snapshot
- `ExecutionContext` — mutable per-node status tracking during a run
- `ExecutionResult` — immutable terminal outcome of a completed run
- `ExecutionStatus` — `IDLE → QUEUED → RUNNING → {COMPLETED | FAILED | SKIPPED | CANCELLED}`
- `StableExecutionIds` — deterministic ID generation for execution artifacts

**Public API**: All types listed above.

**Allowed dependencies**: Workflow context (`org.hammer.audio.workflow`), Java SE.

**Dependency rules**:
- Must not import from `org.hammer.audio.recording.*` (Persistence)
- Must not import from `org.hammer.audio.ui.*` or `org.hammer.*` (Visualization)
- Must not import from `org.hammer.audio.experimental.*`
- Must not import from `org.hammer.audio.plugin.*`

**Ownership**: Workflow domain team.

---

## 4. Validation

**Responsibility**: Structural validation of workflow graphs: duplicate IDs, port compatibility,
cyclic edge detection, dangling references and data-type compatibility.

**Module**: `audio-core`

**Location**: Currently co-located in the Workflow package as `WorkflowValidator`. Can be extracted
to its own package (`org.hammer.audio.workflow.validation`) when it grows.

**Key types**:
- `WorkflowValidator` — validates a `Workflow` instance and returns a list of violation messages

**Public API**: `WorkflowValidator`

**Allowed dependencies**: Workflow context, Java SE.

**Dependency rules**: Same as Workflow context (it is embedded in that package).

**Ownership**: Workflow domain team.

---

## 5. Persistence

**Responsibility**: Durable storage of audio data and (future) workflow snapshots.

**Module**: `audio-dsp`

**Package root**: `org.hammer.audio.recording`

**Key types** (current — audio persistence):
- `AudioBlockRecordingWriter` — writes a sequence of `AudioBlock` values to a file
- `AudioBlockRecordingReader` — reads them back in order
- `AudioBlockRecordingFormat` — binary format constants

**Future scope**: JGit-based workflow snapshot persistence (branch, commit, merge, history). When
implemented, that code will live in a new module or under a dedicated package such as
`org.hammer.audio.workflow.persistence`.

**Public API**: `AudioBlockRecordingWriter`, `AudioBlockRecordingReader`, `AudioBlockRecordingFormat`

**Allowed dependencies**: Audio Processing context (`AudioBlock`, `AudioFormatDescriptor`), Java SE.

**Dependency rules**:
- Must not import from `org.hammer.audio.ui.*` or `org.hammer.*` (Visualization)
- Must not import from `org.hammer.audio.workflow.*` (Workflow) — current audio persistence is
workflow-independent; workflow persistence will be in a separate package
- Must not import from `org.hammer.audio.experimental.*`

**Ownership**: Infrastructure team.

---

## 6. Visualization

**Responsibility**: Pixel-aware rendering, Swing UI panels and UI-independent visualization models.
This is the only context that knows about pixels, panel dimensions or Swing/AWT types.

**Module**: `audio-app`, `audio-experimental-acoustic`

**Package roots**:

|                        Package                         |            Module             |                    Responsibility                    |
|--------------------------------------------------------|-------------------------------|------------------------------------------------------|
| `org.hammer.audio.ui`                                  | `audio-app`                   | `WaveformRenderer` — snapshot-to-pixel conversion    |
| `org.hammer.audio.ui.theme`                            | `audio-app`                   | `UiTheme`, `PlotRenderTheme` — color and font tokens |
| `org.hammer`                                           | `audio-app`                   | Swing panels and application frame                   |
| `org.hammer.audio.experimental.acoustic.visualization` | `audio-experimental-acoustic` | UI-independent 2D room/track visualization DTOs      |
| `org.hammer.audio.experimental.acoustic.workbench`     | `audio-experimental-acoustic` | Interactive Swing workbench panels for localization  |

**Public API**: Visualization is a terminal context — it is not imported by other contexts.
- `WaveformRenderer` — consumed only by Swing panels in `org.hammer`
- Visualization DTOs in `org.hammer.audio.experimental.acoustic.visualization`

**Allowed dependencies**: Audio Processing, Workflow, Execution, Plugin API (`audio-plugin-api`).

**Dependency rules**:
- Must never be imported by Audio Processing, Workflow, Execution, Validation or Persistence
contexts (these are enforced by the `ArchitectureBoundaryTest`).
- The `audio-app` module must not import concrete plugin packages at compile time; it uses
`audio-plugin-api` only and loads plugins via `ServiceLoader` at runtime.

**Ownership**: UI team.

---

## 7. Benchmarking

**Responsibility**: Quality metrics, evaluation procedures and regression tracking for both JMH
micro-benchmarks (ring buffer, FFT, signal generators) and acoustic/classification experiment
benchmarks.

**Modules**: `audio-dsp` (JMH profile), `audio-experimental-acoustic`

**Package roots**:

|                             Package                             |            Module             |                       Responsibility                       |
|-----------------------------------------------------------------|-------------------------------|------------------------------------------------------------|
| `org.hammer.audio.benchmark`                                    | `audio-dsp` (JMH profile)     | JMH micro-benchmarks for ring buffer, FFT, sample decoding |
| `org.hammer.audio.experimental.acoustic.benchmark`              | `audio-experimental-acoustic` | Localization/classification benchmark framework            |
| `org.hammer.audio.experimental.acoustic.benchmark.localization` | `audio-experimental-acoustic` | Position, velocity, frequency and Doppler error metrics    |
| `org.hammer.audio.experimental.acoustic.benchmark.classifier`   | `audio-experimental-acoustic` | Accuracy, precision/recall, confusion matrix metrics       |

**Public API**:
- JMH benchmarks (`@Benchmark` methods) — run via `./mvnw -Pjmh package`
- `BenchmarkContribution` interface in `audio-plugin-api` — registers a named metric and unit
- `LocalizationErrorMetric`, `ClassificationAccuracyMetric`, etc. in the experimental module

**Allowed dependencies**: Audio Processing, Workflow (for scenario-grounded benchmarks).

**Dependency rules**:
- JMH benchmarks (`org.hammer.audio.benchmark`) must not import from `org.hammer.audio.ui.*`
or `org.hammer.*` (Visualization)
- Experimental acoustic benchmarks must not import from `org.hammer.audio.ui.*` or `org.hammer.*`

**Ownership**: Quality / research team.

---

## 8. Collaboration

**Responsibility**: Multi-user real-time concurrent editing of workflow graphs, including presence
awareness, per-user undo/redo, and conflict-free update merging. Provides a gateway between
browser clients and the Workflow/Persistence context.

**Status**: Future. Architecture is documented in
[`docs/architecture/collaborative-workflow-platform.md`](collaborative-workflow-platform.md).

**Planned packages**:
- `org.hammer.audio.workflow.collaboration` — collaboration session model, awareness events
- `org.hammer.audio.workflow.collaboration.sync` — Yjs bridge and WebSocket adapter

**Planned public API**:
- Collaboration session API (session join/leave, awareness update)
- `WorkflowOperation` stream for broadcasting semantic edits (reuses Workflow context type)

**Allowed dependencies**: Workflow context, Persistence context, Java SE.

**Dependency rules**:
- Must not import from Audio Processing packages
- Must not import from Visualization packages
- Must not import from Benchmarking packages

**Ownership**: Collaboration infrastructure team.

---

## Dependency rule summary

The table below uses ✓ (allowed) and ✗ (forbidden) to summarise which context (row) may import
types from which other context (column).

|                   | Audio Proc | Workflow | Execution | Validation | Persistence | Visualization | Benchmarking | Collaboration |
|-------------------|:----------:|:--------:|:---------:|:----------:|:-----------:|:-------------:|:------------:|:-------------:|
| **Audio Proc**    |     —      |    ✗     |     ✗     |     ✗      |      ✗      |       ✗       |      ✗       |       ✗       |
| **Workflow**      |     ✗      |    —     |     ✗     |     ✗      |      ✗      |       ✗       |      ✗       |       ✗       |
| **Execution**     |     ✗      |    ✓     |     —     |     ✗      |      ✗      |       ✗       |      ✗       |       ✗       |
| **Validation**    |     ✗      |    ✓     |     ✗     |     —      |      ✗      |       ✗       |      ✗       |       ✗       |
| **Persistence**   |     ✓      |    ✗     |     ✗     |     ✗      |      —      |       ✗       |      ✗       |       ✗       |
| **Visualization** |     ✓      |    ✓     |     ✓     |     ✓      |      ✓      |       —       |      ✗       |       ✗       |
| **Benchmarking**  |     ✓      |    ✓     |     ✗     |     ✗      |      ✗      |       ✗       |      —       |       ✗       |
| **Collaboration** |     ✗      |    ✓     |     ✗     |     ✓      |      ✓      |       ✗       |      ✗       |       —       |

---

## Enforcement

Architecture boundary rules are enforced at two levels:

### 1. Maven module boundaries (compile-time)

The Maven module dependency graph enforces coarse-grained boundaries automatically. See
[`ARCHITECTURE.md`](../../ARCHITECTURE.md) for the module dependency graph. Key rules:

- `audio-core`, `audio-geometry`, `audio-acquisition`, `audio-dsp` must not declare compile
  dependencies on `audio-app`, `audio-plugin-api` or `audio-experimental-acoustic`.
- `audio-plugin-api` must not declare any `audio-*` dependency.
- `audio-app` must declare `audio-experimental-acoustic` as `runtime` scope only (ServiceLoader
  discovery); never as `compile`.

### 2. ArchUnit fitness tests (bytecode-level)

`ArchitectureFitnessTest` in `audio-app` uses [ArchUnit](https://www.archunit.org) to analyse
compiled bytecode and enforces the following rules automatically on every `mvn verify` run:

|                    Test method                    |                                  Bounded-context rule                                  |
|---------------------------------------------------|----------------------------------------------------------------------------------------|
| `workflowContextDoesNotDependOnSwing`             | Workflow must not depend on `javax.swing` / `java.awt`                                 |
| `workflowContextDoesNotDependOnJGit`              | Workflow must not depend on `org.eclipse.jgit`                                         |
| `workflowContextDoesNotDependOnPersistence`       | Workflow (incl. Validation) must not depend on `org.hammer.audio.recording`            |
| `executionContextDoesNotDependOnPersistence`      | Execution must not depend on `org.hammer.audio.recording`                              |
| `executionContextDoesNotDependOnVisualization`    | Execution must not depend on `org.hammer.audio.ui` / Swing / AWT                       |
| `noCyclicDependenciesBetweenBoundedContextSlices` | No cyclic dependencies between bounded-context package slices under `org.hammer.audio` |

Note: React and Yjs are JavaScript frameworks with no Java package equivalent in this project.
The Workflow context is protected from any Java-based UI or collaboration framework by the rules
above. The Collaboration context is future; its rules will be added when the package is created.

### 3. Source-level import checks (fitness tests)

`ArchitectureBoundaryTest` in `audio-app` walks all Java source files and asserts that:

- Stable audio packages do not import experimental packages.
- Stable modules do not import UI or app packages.
- Module POM dependencies respect the compile-scope rules above.
- `audio-app` sources do not import concrete plugin packages.
- `audio-plugin-api` sources do not import host or concrete plugin packages.
- The Workflow domain model (`org.hammer.audio.workflow`, excluding the `execution` subpackage)
  does not import from the Execution context (`org.hammer.audio.workflow.execution`).
- The Execution context does not import from Persistence or Visualization.
- The Persistence context (`org.hammer.audio.recording`) does not import Visualization.
- The Benchmarking context does not import Visualization.

---

## Future reuse

Each bounded context is designed to be independently reusable as a module in a broader graph
platform:

- **Workflow** and **Execution** contexts have no audio-specific dependencies and can be extracted
  into a `workflow-core` library shared with the Taxonomy project or other graph editors.
- **Collaboration** (when implemented) will be a standalone WebSocket service.
- **Persistence** (JGit layer) will be a standalone service wrapping a Git repository.
- **Audio Processing** stable modules (`audio-core`, `audio-dsp`, etc.) are already independently
  deployable.

