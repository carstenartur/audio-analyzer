# Audio Analyzer architecture

Audio Analyzer is organized as a layered Java 21 platform. The core rule is simple: stable audio,
DSP, workflow and plugin contracts must remain independent of Swing UI code and experimental research
modules.

This page gives the high-level architecture. Detailed bounded-context rules live in
[`docs/architecture/bounded-contexts.md`](docs/architecture/bounded-contexts.md). Experimental
localization details live in [`docs/plugins/acoustic-localization.md`](docs/plugins/acoustic-localization.md).

## Architectural goals

The architecture is designed to support:

- reproducible audio analysis;
- deterministic tests without live audio hardware;
- clear separation between stable platform code and experimental research code;
- desktop UI today, with reusable non-UI models underneath;
- plugin-provided workbenches without compile-time app dependencies on concrete plugins;
- gradual hardening through architecture fitness tests and QA evidence.

## Runtime flow

```text
capture or generator
  -> AudioBlock
  -> AudioRingBuffer
  -> DSP pipeline
  -> analyzers / workbench pipelines
  -> immutable snapshots
  -> Swing views and exports
```

Everything below the UI layer works in normalized audio-domain data, not pixels. UI code may render
snapshots, export images or show dialogs, but DSP and analysis code must not depend on Swing, AWT or
application packages.

## Maven modules

```text
audio-core
audio-geometry
audio-acquisition           -> audio-core, audio-geometry
audio-dsp                   -> audio-core
audio-plugin-api            stable plugin contracts; no audio-* dependencies
audio-experimental-acoustic -> audio-core, audio-geometry, audio-acquisition,
                               audio-dsp, audio-plugin-api
audio-app                   -> audio-core, audio-dsp, audio-plugin-api
                               runtime: audio-experimental-acoustic plugin
```

Module responsibilities:

- `audio-core` contains immutable audio-domain types, snapshots, ring buffer and workflow models.
- `audio-geometry` contains reusable 2D geometry and localization constraints.
- `audio-acquisition` contains microphone metadata, arrays, multichannel sources and sample clocks.
- `audio-dsp` contains sample decoding, DSP processors, FFT, analyzers, diagnosis and recording
  format support.
- `audio-plugin-api` contains stable host-facing plugin contracts only.
- `audio-experimental-acoustic` contains research code for localization, datasets, simulation and
  acoustic workbenches.
- `audio-app` contains Swing UI, JavaSound/demo wiring, exports and plugin hosting.

## Boundary rules

Stable modules must not import from `audio-app` or `audio-experimental-acoustic`. The application host
must use plugin contracts rather than concrete experimental classes. Experimental modules may depend
on stable modules and `audio-plugin-api`, but they should not push research-specific assumptions back
into stable APIs prematurely.

Architecture checks enforce these expectations:

- `ArchitectureBoundaryTest` checks source imports and Maven dependency declarations.
- `ArchitectureFitnessTest` uses ArchUnit to enforce workflow/execution boundaries and detect package
  cycles.
- The bounded-context document defines package ownership, dependency directions and public APIs.

## Core audio model

`AudioBlock` is the central data object. It is immutable, carries normalized float samples, frame index
and timestamp metadata, and can be passed safely through capture, DSP, recording and analysis paths.

`AudioFormatDescriptor` describes sample rate, channel count and sample format independently of
JavaSound or UI classes.

`AudioRingBuffer` is a bounded single-producer/single-consumer buffer used to decouple capture from
analysis or UI consumers.

## DSP and analysis

DSP stages implement `DSPProcessor` and can be composed in a `DSPPipeline`. Analysis modules produce
immutable snapshots rather than changing the input block.

Important current components:

- `SampleDecoder` converts raw capture bytes to normalized floats;
- `Fft` and `SpectrumAnalyzer` produce spectrum snapshots;
- `MeasurementCalculator` derives RMS, peak, clipping and dominant-frequency values;
- `SpectrogramAnalyzer` maintains spectrogram history;
- `DiagnosisAnalyzer` produces immutable findings;
- `StereoDelayAnalyzer` estimates broad stereo delay and direction from two-channel input;
- recording reader/writer classes preserve `AudioBlock` sequences as `.aar` files.

Stereo delay is not full 3D localization. It is a broad two-microphone time-delay estimate and can be
invalidated by reflections, channel mismatch, geometry errors or weak correlation.

## UI layer

Swing code lives in `audio-app`. It renders snapshots, starts/stops capture services, opens plugin
views and writes user-facing exports. UI code may depend on stable audio models, but stable audio
models must not depend on UI classes.

Generated documentation screenshots are produced by `DocImageRenderer`. They are part of the release
QA process and should be regenerated from the release candidate rather than manually edited.

## Plugin architecture

`audio-plugin-api` provides stable contribution interfaces. The app discovers plugins through Java
`ServiceLoader` and should not import concrete plugin classes.

A plugin can contribute:

- signal sources;
- repeatable experiments or scenarios;
- processing pipelines;
- snapshot streams;
- visualization descriptions;
- calibration procedures;
- benchmark metrics;
- export formats;
- Swing views through `ViewContribution`.

The acoustic-localization plugin is the first substantial reference implementation. Future workbench
domains should follow the same boundary: stable metadata through `audio-plugin-api`, concrete
experimental logic in a dedicated module.

## Workflow model

The workflow domain in `audio-core` separates design-time structure from runtime execution state.

```text
Workflow
  -> ExecutionSnapshot
  -> ExecutionPlan
  -> ExecutionContext
  -> ExecutionResult
```

Design-time workflow objects are immutable and framework-independent. Execution state is isolated in
`org.hammer.audio.workflow.execution`. This separation supports reproducibility, concurrent editing,
headless execution and future batch or collaborative workflows.

Workflow edits are represented by semantic operations such as create node, connect ports, rename node
and update property. `WorkflowOperationLog` applies and records these operations so replay and undo can
be deterministic.

## Experimental acoustic localization

The `audio-experimental-acoustic` module contains research code for microphone-array localization and
wingbeat analysis. It currently provides deterministic simulations, tracking, benchmark metrics,
HumBugDB import, feature extraction, rule-based classification, generator calibration and workbench
views.

This module is intentionally isolated because its assumptions are still research-grade. Real-world
localization requires synchronized channels, calibrated geometry, explicit timing-error budgets and
benchmark evidence. Those topics are tracked in issues #136, #138 and #139.

## Extension guidelines

When adding new functionality:

1. put reusable audio-domain types in stable modules;
2. keep UI rendering in `audio-app`;
3. use `audio-plugin-api` for host-facing plugin metadata;
4. isolate experimental research code in an experimental module;
5. add deterministic tests before making release-facing claims;
6. update documentation and screenshots when user-visible behavior changes.

## Known architectural debt

- The `org.hammer.audio` package remains split across app and DSP code for compatibility.
- Some legacy UI paths still consume compatibility models where newer snapshot-based flows would be
  cleaner.
- Real microphone-array calibration is not yet a first-class stable subsystem.
- Documentation screenshots need continued visual QA as the Swing layout evolves.

