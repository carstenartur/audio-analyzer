# Experimental acoustic localization architecture

## Architecture review

The current project is already layered around acquisition, immutable `AudioBlock` values, ring
buffers, DSP processors, analysis snapshots and Swing visualization. That makes it a suitable host
for experimental acoustic localization, provided the research code stays outside the stable core.

Existing reusable building blocks:

- `org.hammer.audio.core` owns stable normalized audio blocks and format metadata.
- `org.hammer.audio.dsp` owns generic block-to-block processing composition.
- `org.hammer.audio.analysis` owns reusable FFT and analyzer contracts.
- `org.hammer.audio.localization` owns the existing production-adjacent stereo delay diagnostic.
- `org.hammer.audio.signal` owns deterministic signal generation.
- `org.hammer.audio.ui` and `org.hammer` own rendering and Swing boundaries.

## Coupling analysis

The experimental module must not depend on Swing panels, JavaSound device classes or application
frame state. The dependency direction is:

```text
org.hammer.audio.core
org.hammer.audio.geometry
org.hammer.audio.acquisition
        ▲
        │
org.hammer.audio.experimental.acoustic
        │
        ├─ calibration estimators and synthetic fixtures
        ├─ simulation
        └─ visualization DTOs
```

Core packages do not import `org.hammer.audio.experimental.*`. The plugin imports stable audio,
geometry and acquisition APIs. Visualization output is represented as DTOs so Swing, web or notebook
renderers can consume it without coupling DSP to a UI framework.

## What belongs in stable modules

Stable modules contain abstractions useful beyond insects or research prototypes:

- microphone metadata and multi-channel source contracts;
- immutable microphone-array calibration profiles;
- per-channel static timing offset, affine drift, residual/jitter error, gain and polarity metadata;
- synchronization mode/status values and deterministic timing-error assessments;
- sample clock handling and timestamped `AudioBlock` frames;
- 2D geometry primitives, rays and localization constraints;
- existing FFT, DSP pipeline and analysis contracts;
- recording/replay hooks that operate on `AudioBlock` rather than a concrete device library.

The stable acquisition model records calibration evidence and quality, but it does not detect
calibration events, run cross-correlation, resample a device or choose a localization algorithm.
Those operations remain experimental and replaceable.

## What belongs in the acoustic plugin

`org.hammer.audio.experimental.acoustic` contains research-specific and replaceable logic:

- deterministic synthetic calibration-pulse fixtures;
- offset observation through normalized cross-correlation;
- drift estimation from repeated calibration events;
- calibrated TDOA decoration and rejection of unusable synchronization evidence;
- wingbeat/narrow-band frequency tracking;
- cross-correlation and GCC-PHAT TDOA experiments;
- delay-and-sum beamforming over candidate grids;
- example mosquito-localization pipeline composition;
- room simulation, moving emitters, reflections and noise;
- visualization-ready debug frames, workbench diagnostics and heatmaps.

Tracking and localization snapshots carry the synchronization assessment that was actually used.
Workbench views and Markdown/CSV/JSON exports render the same evidence instead of inventing a second
UI-only quality model.

This code may evolve quickly and may be benchmarked or replaced without changing the stable
application model.

## Supported synchronization paths

The architecture distinguishes three explicit modes:

- `NOMINAL_SHARED_CLOCK` for one verified hardware clock;
- `CALIBRATED_OFFSET` for a valid static inter-channel correction;
- `DRIFT_COMPENSATED` for an affine offset model derived from repeated calibration events.

Each observation is assessed as `TRUSTED`, `DEGRADED` or `REJECTED` against a caller-defined timing
error budget. Rejected evidence stops calibrated TDOA/localization before a position is reported.

This does not claim that arbitrary USB microphones are automatically synchronized. Automatic beacon
detection, cycle-slip repair and continuous sample-rate conversion remain outside the implemented
path and require separate measured validation.

## Maven modularization

The repository uses a seven-module Maven reactor. The split is the build-level compatibility
boundary for stable audio APIs, plugin contracts, Swing application code and research-only acoustic
localization code.

- `audio-core` for `AudioBlock`, format metadata and generic immutable domain models;
- `audio-geometry` for reusable 2D geometry primitives and localization constraints;
- `audio-acquisition` for synchronized source, microphone-array and calibration contracts;
- `audio-dsp` for reusable FFT, DSP, analyzer, spectrogram, diagnosis and stereo-delay logic;
- `audio-plugin-api` for stable plugin contracts with no dependencies on concrete audio modules,
  host code or plugins;
- `audio-experimental-acoustic` for calibration algorithms and insect-localization research;
- `audio-app` for Swing UI, JavaSound/demo wiring, export, plugin hosting and packaging.

The acoustic plugin depends on stable modules plus `audio-plugin-api`. The Swing app compiles
against `audio-plugin-api` and includes the concrete acoustic plugin only as a runtime dependency so
Java `ServiceLoader` can discover it.

## Enforced dependency guards

`audio-app/src/test/java/org/hammer/audio/ArchitectureBoundaryTest.java` currently fails the build
if:

- stable modules import `org.hammer.audio.experimental.*`;
- stable modules import UI packages or top-level Swing application packages;
- stable module POMs depend on `audio-app`, `audio-plugin-api` or
  `audio-experimental-acoustic`;
- `audio-plugin-api` imports host or concrete plugin packages;
- `audio-app` has a compile-scope dependency on `audio-experimental-acoustic`.

This keeps calibration evidence reusable while ensuring estimation algorithms, synthetic fixtures
and workbench adapters cannot leak into stable acquisition or core packages.

## Rationale

A build-level module boundary is the smallest backwards-compatible refactor that prevents
experimental research code from becoming an implicit dependency of the stable platform. Calibration
quality is first-class stable data because acquisition, recording, replay, localization and export
all need to agree on it. The algorithms that produce or consume that evidence remain replaceable and
benchmarkable in the experimental module.
