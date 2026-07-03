# Acoustic localization plugin guide

This guide expands the [plugin overview](../acoustic-localization.md). It documents how to use the
experimental workbenches, what evidence they produce and which limitations must be respected when
interpreting results.

The plugin is a research subsystem. It is designed for reproducible experiments and benchmarkable
algorithm development, not for production mosquito tracking or species classification.

## Workbench entry points

Start the Swing app and open the **Plugins** menu. Under **Experimental Acoustic Localization** there
are two main views:

- **Acoustic Localization Workbench (experimental)** — deterministic simulation scenarios, tracking,
  playback and export.
- **Imported Recording Workbench (experimental)** — local HumBugDB import, feature extraction,
  baseline classification and dataset analysis.

Both views run inside the host application. Stable plugin contracts stay in `audio-plugin-api`; the
experimental implementation stays in `audio-experimental-acoustic`.

## Simulation workbench

Use the simulation workbench when you need a reproducible localization run without live hardware.

Typical workflow:

1. choose a deterministic scenario;
2. configure block size, FFT size, peak detection, frequency band, candidate grid and TDOA estimator;
3. run the scenario;
4. inspect the log and room map;
5. step through completed frames with playback controls;
6. export Markdown, CSV or JSON-lines evidence.

The room map shows the room boundary, microphone positions, candidate grid, ground-truth positions
where available and estimated tracks. Track IDs are stable within the run so identity persistence can
be inspected frame by frame.

## Available scenarios

The scenario list is provided by `SimulationScenarios.all()`. Current scenario families include:

- a stationary single source;
- two close frequencies that challenge naive clustering;
- a noisy room;
- a moving source;
- Doppler-oriented movement toward and across the array;
- two moving sources;
- a reflected environment;
- two deterministic mosquito-like wingbeat emitters.

These scenarios are synthetic. They are valuable for regression tests and algorithm comparison, but
they do not prove real-world performance.

## Configurable simulation parameters

Common workbench parameters:

- **Block size** — frames processed per block.
- **FFT size** — frequency-analysis window length.
- **Max peaks** — number of detected peaks per channel per block.
- **Minimum SNR** — minimum peak-to-median ratio for accepting peaks.
- **Frequency band** — lower and upper bound for peak search.
- **Candidate grid** — number of 2D grid steps used by beamforming.
- **TDOA estimator** — GCC-PHAT or cross-correlation.

Prefer small, deterministic parameter changes when comparing algorithms. Large changes can alter
runtime budget, peak selection and tracking behavior at the same time.

## Log and budget output

Each processed block produces a compact log entry with frame index, timestamp, detected clusters,
tracked sources and processing time. Completed runs also expose aggregate budget statistics.

When a frame exceeds the configured `FrameSchedule` processing budget, the log and exports mark it as
over budget. Budget warnings are diagnostic information; they do not abort processing and do not, by
themselves, imply an incorrect result.

Budget warnings are most useful for comparing configurations:

- repeated warnings suggest that FFT size, grid resolution or peak count may be too expensive;
- occasional spikes can be caused by JIT warmup, garbage collection or host scheduling;
- real-time claims require target-hardware evidence, not only development-machine timings.

Programmatic access is available through `WorkbenchRunResult.overBudgetFrameCount()` and
`isFrameOverBudget(snapshot)`.

## Export formats

After a simulation run, the workbench can export:

- **Markdown** — human-readable run summary, parameters, timing/budget statistics and tracked-source
  table;
- **CSV** — one row per tracked source per frame, including position, confidence and budget flag;
- **JSON-lines** — one JSON object per frame for offline processing.

Use these exports as reviewable evidence in issues, PRs and benchmark notes.

## Imported recording workbench

The imported-recording workbench is local-only and offline-first. It expects a local HumBugDB export
root and does not download data automatically.

Typical workflow:

1. open the imported-recording workbench from the plugin menu;
2. select a local HumBugDB export directory;
3. import metadata and WAV references into a `DatasetManifest`;
4. inspect recordings, labels and metadata;
5. replay feature extraction and rule-based classification for selected recordings;
6. review dataset-level evaluation metrics and feature distributions;
7. run generator calibration from extracted feature vectors where available.

Users are responsible for obtaining datasets legally and respecting upstream licenses.

## Classification baseline

The rule-based classifier is intentionally simple and transparent. It uses frequency-oriented rules to
produce a reproducible baseline for evaluation workflows. It is not a trained model and should not be
presented as species-level classifier accuracy.

Evaluation outputs include:

- overall accuracy for the available labels;
- per-label precision and recall;
- confusion matrix;
- feature distribution statistics such as dominant frequency, SNR and harmonic ratios.

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

Implemented components:

- multi-peak frequency detection with parabolic peak refinement;
- cross-channel frequency clustering;
- GCC-PHAT and cross-correlation TDOA estimation;
- delay-and-sum beamforming over 2D candidate grids;
- Kalman-style tracking with identity persistence and confidence decay;
- deterministic simulation with moving emitters, reflections and noise;
- benchmark metrics for localization and classification.

Experimental components:

- feature ranking and comparison;
- synthetic-vs-real distribution comparison;
- generator calibration from imported feature statistics;
- richer visualization and confidence displays.

## Practical hardware notes

The simulation workbench does not require hardware. Real microphone-array experiments do.

For credible real-world localization, prefer:

- a synchronized multi-channel audio interface;
- rigid microphone geometry with measured coordinates;
- known channel mapping;
- calibration clicks or chirps before and after recording;
- residual timing-error estimates;
- rejection criteria when the timing error exceeds the spatial error budget.

Independent USB microphones are not reliable by default. They need an explicit timing model for offset,
drift and buffering jitter. That work is tracked in #136 and is not complete.

## Synchronization limits

TDOA is very sensitive to timing. A one-sample error at 48 kHz corresponds to roughly 7 mm of acoustic
path difference in air. For small arrays and weak sources, clock drift, channel latency and buffering
jitter can dominate the signal.

The current `SampleClock` stores nominal timestamps. It does not compensate arbitrary inter-device
drift or per-channel latency. Treat uncompensated multi-device recordings as demonstration-grade only.

## Interpretation rules

Use these rules when reading results:

- synthetic success is regression evidence, not field validation;
- a precise coordinate is not a precise measurement unless timing and geometry errors are bounded;
- confidence values are algorithm diagnostics, not calibrated probabilities;
- rule-based classification is a baseline, not a biological identification claim;
- exported evidence should include scenario, parameters and limitations.

## Related documentation

- [Synchronization requirements](synchronization.md)
- [Tracking pipeline](tracking.md)
- [Real-world dataset strategy](datasets.md)
- [HumBugDB evaluation baseline](evaluation-baseline.md)
- [Physics and latency limits](physics-and-latency-limits.md)
- [Evaluation metrics](research/evaluation-metrics.md)
- [Research notes](research/README.md)

## Future research directions

Open research work includes:

- sub-sample TDOA interpolation;
- reflection-aware confidence and consistency checks;
- probabilistic multi-source tracking;
- calibrated array profile files;
- 3D geometry;
- benchmark corpora with measured real-world recordings;
- clearer uncertainty propagation into workbench visualizations.
