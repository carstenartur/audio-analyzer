# Experimental acoustic localization

This page is the entry point for the optional acoustic-localization plugin. The plugin is a research
extension for microphone-array experiments, deterministic simulations, wingbeat-feature analysis and
benchmarking. It is **not** a production mosquito detector, species classifier or validated tracking
product.

## Status

The plugin is useful for reproducible experiments because it makes localization assumptions executable:
geometry, frequency detection, TDOA estimation, beamforming, tracking, benchmark metrics and dataset
features can all be inspected in code and through workbench views.

Stable host-facing integration:

- provided by `audio-plugin-api` contracts;
- discovered through Java `ServiceLoader`;
- loaded by `audio-app` as an optional runtime plugin;
- kept separate from stable audio/DSP modules through module and architecture boundaries.

Research status:

- deterministic simulation scenarios are implemented;
- imported-recording and feature-evaluation workflows exist;
- benchmark and generator-calibration infrastructure exists;
- saved array profiles, measured timing calibration and a single-device live/record/replay workflow
  are implemented;
- real-world microphone-array accuracy still requires physical measurements and reference evidence;
  successful software preflight or simulation does not establish it.

## Current capabilities

Implemented capabilities:

- simulation workbench with deterministic room-acoustics scenarios;
- imported-recording workbench for local HumBugDB exports;
- multi-peak frequency detection and cross-channel clustering;
- integer-sample GCC-PHAT, sub-sample GCC-PHAT and cross-correlation TDOA estimators;
- 2D delay-and-sum beamforming over configurable candidate grids;
- Kalman-based source tracking with stable track IDs;
- Doppler-related velocity experiments;
- rule-based wingbeat classification baseline;
- classification and localization benchmark metrics;
- Markdown, CSV and JSON-lines export for workbench runs;
- real-time processing-budget diagnostics for workbench frames;
- saved geometry/calibration profiles and host-device readiness checks;
- managed recordings, recovery and complete/incomplete evidence ZIPs through the microphone workflow.

Experimental or incomplete areas:

- calibrated confidence probabilities for reflections and ambiguous peaks;
- 3D geometry;
- coordinated capture from multiple independent devices and continuous clock reconstruction;
- trained species classification;
- production-grade mosquito or insect tracking.

## Workbenches

### Microphone experiment workflow

Open **`/workbench/experiment`** in the running browser workbench, or **Experiment > Microphone-array
workflow…** in the desktop experiment host. This is the maintained source-to-evidence path for
simulation, a synchronized multichannel JavaSound device and verified `.aarec` replay. Follow
[Microphone experiments](../features/microphone-experiments.md) for the complete setup, calibration,
recording, finalization, recovery and evidence round trip.

The standalone acoustic provider currently uses a fixed 3 × 2 m candidate region, 150–2500 Hz detection
band and integer-sample GCC-PHAT. Profile geometry edits do not expand that region. The separate
simulation workbench below exposes algorithm parameter choices, including sub-sample GCC-PHAT.

### Simulation workbench

Open **Plugins > Experimental Acoustic Localization > Open: Acoustic Localization Workbench
(experimental)** to run deterministic scenarios through the tracking pipeline. The view provides:

- scenario selection;
- core pipeline parameters;
- per-frame log output;
- 2D room map with microphones, ground truth and estimated tracks;
- playback controls for completed runs;
- budget-overrun diagnostics;
- Markdown, CSV and JSON-lines export.

Use this workbench for algorithm inspection and reproducible demonstration, not for real-world claims.

### Imported recording workbench

Open **Plugins > Experimental Acoustic Localization > Open: Imported Recording Workbench
(experimental)** to inspect a local HumBugDB export. The workflow is offline-first: the application
expects the user to provide a local dataset directory and does not download third-party data.

The workbench supports:

- local dataset import;
- recording metadata inspection;
- feature extraction;
- rule-based classification evaluation;
- feature distribution analysis;
- generator calibration from imported feature vectors where available.

## Pipeline overview

```text
AudioBlock
  -> MultiPeakDetector
  -> FrequencyClusterer
  -> TdoaEstimator
  -> DelayAndSumBeamformer
  -> SourceTracker
  -> TrackingSnapshot
```

The pipeline is designed to be deterministic and benchmarkable. It records processing time and budget
compliance, but it does not guarantee real-time scheduling on all hardware.

## Data and benchmarks

The plugin separates three evidence levels:

1. **Synthetic scenarios** — deterministic, reproducible and suitable for regression tests.
2. **Imported real recordings** — useful for feature statistics and baseline classification when the
   dataset license and provenance are handled by the user.
3. **Real microphone-array experiments** — the single-device acquisition and evidence workflow is
   implemented; measured geometry, timing calibration and independent reference results are still
   required before making physical accuracy claims.

Do not treat synthetic success as proof of real-world localization reliability.

## Hardware and synchronization limits

Accurate TDOA requires synchronized samples across channels. The current `SampleClock` model stores
nominal timing. The implemented `MicrophoneArrayCalibration` / `CalibratedTdoaEstimator` path models
measured per-channel offsets and affine drift and rejects timing uncertainty beyond its configured
budget. It does not automatically measure calibration, aggregate independent USB devices, correct
cycle slips or continuously reconstruct a common sample clock.

For credible real-world localization, experiments need:

- known microphone positions;
- a shared sample clock or measured inter-device timing model;
- calibration recordings before and after experiments;
- residual-error estimates;
- rejection or downgrading of results when the synchronization error exceeds the localization budget.

The [synchronization guide](acoustic-localization/synchronization.md) separates this implemented
foundation from unsupported automatic synchronization. The
[real-world workflow](acoustic-localization/research/hardware/real-world-localization-workflow.md)
describes the current acquisition boundary and the remaining hardware validation.

## Documentation

Detailed pages:

- [Workbench and pipeline details](acoustic-localization/README.md)
- [Synchronization requirements](acoustic-localization/synchronization.md)
- [Tracking pipeline](acoustic-localization/tracking.md)
- [Real-world dataset strategy](acoustic-localization/datasets.md)
- [HumBugDB evaluation baseline](acoustic-localization/evaluation-baseline.md)
- [Physics and latency limits](acoustic-localization/physics-and-latency-limits.md)
- [Research notes](acoustic-localization/research/README.md)

Related implementation history and research topics:

- Issue #136 — timing-calibration foundation;
- Issue #138 — localization algorithm improvements;
- Issue #139 — real-world microphone-array workflow.

These issue references identify the work's history; their presence does not mean that the implemented
capabilities above are still future work.

