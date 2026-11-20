# Acoustic localization plugin guide

This guide expands the [plugin overview](../acoustic-localization.md). It documents how to use the
experimental workbenches, what evidence they produce and which limitations must be respected when
interpreting results.

The plugin is a research subsystem. It is designed for reproducible experiments and benchmarkable
algorithm development, not for production mosquito tracking or species classification.

## Workbench entry points

Launch the desktop host with [Getting started](../../getting-started.md#explore-a-signal-in-the-desktop-workbench)
and open the **Plugins** menu. Under **Experimental Acoustic Localization** there are two plugin views:

- **Acoustic Localization Workbench (experimental)** — deterministic simulation scenarios, tracking,
  playback and export.
- **Imported Recording Workbench (experimental)** — local HumBugDB import, feature extraction,
  baseline classification and dataset analysis.

Both views run inside the host application. Stable plugin contracts stay in `audio-plugin-api`; the
experimental implementation stays in `audio-experimental-acoustic`.

For saved array profiles, live acquisition, `.aarec` replay and evidence ZIPs, open **Experiment >
Microphone-array workflow…** or the browser route **`/workbench/experiment`**. The
[microphone operator guide](../../features/microphone-experiments.md) covers that complete workflow.
Its current fixed search region and DSP defaults differ from this configurable simulation view.

## Simulation workbench

Use the simulation workbench when you need a reproducible localization run without live hardware.

Typical workflow:

1. Open **Plugins > Experimental Acoustic Localization > Open: Acoustic Localization Workbench
   (experimental)** and select **single-source** for a first run.
2. Keep the default parameters for the baseline; record any subsequent changes to block/FFT size,
   peak detection, frequency band, candidate grid or TDOA estimator.
3. Click **Run** and wait for the result. Inspect the frame log, room map and processing-budget output.
4. Step through completed frames with the playback controls to compare tracks against the scenario's
   known source positions. Playback inspects completed results; it is separate from acquiring audio.
5. Open the **Markdown**, **CSV** or **JSON-lines** tab, select and copy its complete text, and save it
   in your own `.md`, `.csv` or `.jsonl` file. Copy the **Benchmark** tab into a separate `.md` file when
   needed. Retain the scenario and parameter summary with per-frame output so a later run can use the
   same inputs and configuration.
6. For an algorithm comparison, change one parameter or estimator, run the same scenario again and
   save the second result separately. Keep machine-dependent timing separate from localization output.

These copied plugin reports contain analysis results. To preserve generated input as an actual recording and
replay it, use the [standalone simulation round trip](../../features/microphone-experiments.md#complete-a-first-simulation).

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
- **TDOA estimator** — integer-sample `GCC_PHAT` (default), interpolated `SUB_SAMPLE_GCC_PHAT`, or
  `CROSS_CORRELATION`.

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

After a simulation run, the workbench fills read-only report tabs:

- **Markdown** — human-readable run summary, parameters, timing/budget statistics and tracked-source
  table;
- **CSV** — one row per tracked source per frame, including position, confidence and budget flag;
- **JSON-lines** — one JSON object per frame for offline processing.

To save a report, open its tab, select and copy all text, then paste it into your own file with the
corresponding `.md`, `.csv` or `.jsonl` extension. The **Benchmark** tab can be saved the same way as
Markdown. There is no report-export button in this panel. Use the saved reports as reviewable evidence
in issues, PRs and benchmark notes.

## Imported recording workbench

The imported-recording workbench is local-only and offline-first. It expects a local HumBugDB export
root and does not download data automatically.

Typical workflow:

1. Open **Plugins > Experimental Acoustic Localization > Open: Imported Recording Workbench
   (experimental)**.
2. Use **Browse…** to select a local HumBugDB export root containing `data/metadata/*.csv` and its WAV
   files, then click **Import**. This is a dataset importer, not the `.aarec` experiment replay action.
3. Wait for import and evaluation, then inspect the manifest, analytics, feature histograms and
   evaluation summary. Select an entry in **Recording** and use **Replay analysis** to inspect it.
4. Select **Calibration scope** and choose **Run calibration** to compare/tune the synthetic generator
   against extracted features. This calibrates a signal generator; it does not measure microphone
   timing or make a live array ready.
5. Retain the exact dataset release, labels, WAV files and configuration alongside any reported metric.
   Follow the [evaluation baseline](evaluation-baseline.md) for the input structure, report meanings
   and programmatic report generation.

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
- integer-sample and sub-sample GCC-PHAT, plus cross-correlation TDOA estimation;
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

Independent USB microphones are not reliable by default. The timing-calibration foundation can
represent measured offsets, affine drift and uncertainty, but the current live workflow opens one
multichannel JavaSound device. It does not aggregate several USB devices or continuously align their
sample streams. See [what remains unsupported](synchronization.md#what-remains-experimental-or-unsupported).

## Synchronization limits

TDOA is very sensitive to timing. A one-sample error at 48 kHz corresponds to roughly 7 mm of acoustic
path difference in air. For small arrays and weak sources, clock drift, channel latency and buffering
jitter can dominate the signal.

The current `SampleClock` stores nominal timestamps. `MicrophoneArrayCalibration` and
`CalibratedTdoaEstimator` apply a measured timing model separately; they do not automatically compensate
arbitrary buffering, cycle slips or changing drift. A replay using stored calibration currently checks
expiry against the current wall clock, so a now-expired original profile can prevent historical replay.
The [operator guide](../../features/microphone-experiments.md#current-calibration-limit-for-historical-replay)
records that source-audited limit.

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

- stronger validation of sub-sample estimates under real noise and reflections;
- calibrated uncertainty beyond the implemented peak diagnostics and TDOA consistency checks;
- probabilistic multi-source tracking;
- 3D geometry;
- benchmark corpora with measured real-world recordings;
- clearer uncertainty propagation into workbench visualizations.

