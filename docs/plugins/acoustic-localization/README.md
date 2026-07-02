# Acoustic localization plugin details

This detail page expands the [plugin entry page](../acoustic-localization.md). The package is an
experimental research subsystem for real-time localization and tracking of weak or insect-like sound
sources. It is not a production mosquito detector.

## How to run the workbench

The Plugins menu in Audio Analyzer exposes an interactive **Acoustic Localization Workbench
(experimental)** that lets you run deterministic simulation scenarios through the full tracking
pipeline without a microphone array. The same plugin also exposes an **Imported Recording
Workbench (experimental)** for local HumBugDB inspection and evaluation.

### Opening the workbench

1. Start Audio Analyzer.
2. Open the **Plugins** menu in the menu bar.
3. Expand the **Experimental Acoustic Localization** submenu.
4. Click **Open: Acoustic Localization Workbench (experimental)** for simulation scenarios, or
   **Open: Imported Recording Workbench (experimental)** for local HumBugDB recordings.

A dialog opens with three areas:

- **Top** — scenario selector, pipeline parameter controls and Run / Stop buttons.
- **Centre left** — log, Markdown, CSV and JSON-lines tabs showing live and post-run output.
- **Centre right** — 2-D room map: room rectangle, microphone positions (blue circles), emitter
  ground-truth positions (green triangles), candidate grid (grey dots) and estimated track
  positions (red circles, labeled by track ID).

### Imported recording workbench

The imported-recording workbench is intentionally local-only and offline-first.

1. Open **Imported Recording Workbench (experimental)** from the same plugin submenu.
2. Paste or browse to a local HumBugDB root directory.
3. Click **Import** to build a `DatasetManifest` from the local WAV/CSV export.
4. Browse the imported recordings in the combo box.
5. Use **Replay analysis** to re-run dominant-frequency tracking, feature extraction and baseline
   rule-based classification for the selected clip.

The left pane shows the imported manifest, the upper-right pane shows one recording's metadata and
analysis summary, and the lower-right pane shows a dataset-level evaluation report.

### Available scenarios

All scenarios from `SimulationScenarios.all()` are listed in the dropdown:

|         Scenario         |                         Description                          |
|--------------------------|--------------------------------------------------------------|
| `single-source`          | One stationary 600 Hz tone in an anechoic room               |
| `two-close-frequencies`  | Two sources at 600 Hz and 640 Hz — challenges naive trackers |
| `noisy-room`             | Single source with broadband background noise                |
| `moving-source`          | Source crossing the room at constant velocity                |
| `moving-toward-array`    | Source approaching the array (Doppler validation)            |
| `moving-across-array`    | Source moving laterally across the array                     |
| `two-moving-sources`     | Two tones with distinct velocities                           |
| `reflected-environment`  | Single source with wall reflections enabled                  |
| `two-mosquito-wingbeats` | Two deterministic wingbeat emitters at close frequencies     |

### Configurable parameters

|   Parameter    |    Default    |                            Meaning                            |
|----------------|---------------|---------------------------------------------------------------|
| Block size     | 1024          | Frames per processing block                                   |
| FFT size       | 1024          | FFT window length (power of two)                              |
| Max peaks      | 3             | Peaks detected per channel per block                          |
| Min SNR        | 2.0           | Minimum peak-to-median SNR                                    |
| Band min / max | 150 – 2500 Hz | Frequency search band                                         |
| Grid steps     | 8             | Steps per room axis; total grid is (steps+1)×(steps+1) points |
| TDOA estimator | GCC-PHAT      | `GCC_PHAT` or `CROSS_CORRELATION`                             |

### Log output format

Each processed block prints one line:

```
Block   12  frame=  12288  time=  768.0 ms  clusters=1  tracks=1  proc=142.3 µs
         [600 Hz]  {id=0 f=600 Hz pos=(1.50,1.00) conf=0.92 n=13}
```

When a frame exceeds the per-block real-time budget, the log line is annotated:

```
Block   17  frame=  17408  time=1088.0 ms  clusters=1  tracks=1  proc=28000.0 µs  ⚠ OVER BUDGET
```

If any frames exceeded the budget, the run-complete summary line also reports the count:

```
⚠ 3 frame(s) exceeded the real-time budget (budget=18.6 µs per block).
```

> **Note:** Budget warnings are **experimental**. They indicate that the configured
> `FrameSchedule` deadline was not met for those frames, but do not abort processing.
> On a development machine, budget overruns are normal and expected — the per-block budget
> assumes a real-time-capable system running at the configured sample rate. See
> [How to interpret budget warnings](#how-to-interpret-budget-warnings) for details.

### Export

After a run completes the three export tabs are populated:

- **Markdown** — human-readable summary with scenario metadata, parameter table, aggregate
  budget statistics (*Budget per block*, *Over-budget frames*) and a per-block table where
  over-budget rows are annotated with `⚠ OVER`.
- **CSV** — one row per tracked source per frame (`frameIndex`, `timestampNs`, `trackId`,
  `frequencyHz`, `posX`, `posY`, …, `budgetExceeded`). The `budgetExceeded` column is
  `true` for frames that exceeded the per-block deadline, `false` otherwise.
- **JSON-lines** — one JSON object per frame, with a `"budgetExceeded"` boolean field for
  each frame, suitable for offline analysis.

Copy the content to a file for archiving.

### How to interpret budget warnings

The `FrameSchedule` is configured with 80 % of the block period as the real-time deadline
(`maxLoadFraction = 0.8`). For a 1024-sample block at 44 100 Hz, the budget is roughly 18.6 ms
(0.8 × 23.2 ms).

Budget warnings do **not** imply incorrect results:

- The pipeline is observational: it records `processingNanos` per frame but never skips or
  aborts processing.
- On a typical development machine the per-frame wall-clock time is far below the budget.
  Occasional spikes (JIT warmup, GC pauses, OS scheduling) may trigger a warning.
- Repeated warnings across many frames on a target real-time system indicate that the
  pipeline configuration should be simplified (smaller FFT, fewer grid steps, lower block rate)
  or the hardware upgraded.

The `WorkbenchRunResult.overBudgetFrameCount()` and `isFrameOverBudget(snapshot)` methods
provide programmatic access to the same information.

### Headless / programmatic use

`WorkbenchScenarioRunner` runs scenarios without Swing:

```java
SimulationScenario scenario = SimulationScenarios.singleSource();
WorkbenchParameters params = WorkbenchParameters.defaults().build();
WorkbenchRunResult result = WorkbenchScenarioRunner.run(scenario, params);
System.out.println(WorkbenchRunExporter.toMarkdown(result));
```

All five required acceptance-criteria scenarios (`singleSource`, `twoCloseFrequencies`,
`movingSource`, `noisyRoom`, `reflectedEnvironment`) are covered by
`WorkbenchScenarioRunnerTest` and `AcousticLocalizationWorkbenchPanelTest` in
`audio-experimental-acoustic`.

For imported recordings, see `HumBugDbImporter`, `DatasetWingbeatEvaluationWorkflow` and
`ImportedRecordingWorkbenchPanel` in `audio-experimental-acoustic`.

---

## Pipeline overview

```text
AudioBlock (multi-channel synchronized frame)
  → MultiPeakDetector (FFT + parabolic refinement per channel)
  → FrequencyClusterer (group peaks across channels)
  → TdoaEstimator (GCC-PHAT or cross-correlation, all microphone pairs)
  → DelayAndSumBeamformer (2D candidate grid scoring)
  → SourceTracker (Kalman smoothing + identity persistence)
  → TrackingSnapshot (immutable per-frame output)
```

**Implemented:**

- Multi-peak frequency detection with parabolic refinement;
- Cross-channel frequency clustering with Hz and cents tolerance;
- GCC-PHAT and cross-correlation TDOA estimators;
- Delay-and-sum beamforming over configurable 2D grids;
- Kalman-based source tracking with identity persistence and confidence decay;
- Doppler radial-velocity estimation and multi-sensor velocity reconstruction;
- Real-time budget tracking via `FrameSchedule` and `ProcessingBudget`;
- Deterministic simulation with moving emitters, reflections and noise;
- HumBugDB dataset import and classification evaluation;
- Benchmark infrastructure with localization and classification metrics.

**Experimental:**

- Feature ranking and comparison for classifier development;
- Synthetic-vs-real signal distribution comparison;
- Generator calibration for realistic synthetic data;
- Additional heatmap/confidence visualizations in plugin views.

**Future work:**

- Sub-sample TDOA peak interpolation;
- Probabilistic multi-target data association;
- 3D geometry and multi-story arrays;
- Room impulse-response modeling;
- Trained species classifier.

---

## HumBugDB dataset import

The `HumBugDbImporter` provides local offline-first import of the HumBugDB mosquito dataset:

**How it works:**

1. User provides an absolute path to a local HumBugDB export directory
2. Importer reads metadata CSVs (`data/metadata/*.csv`) and resolves WAV file paths
3. Creates a normalized `DatasetManifest` with recordings, labels and annotations
4. Labels include species, gender, fed status, age when available
5. Manifest can be used for classification evaluation and feature analysis

**No automatic downloads.** Users must obtain HumBugDB from the upstream project and accept its
license terms before using it. See [Real-world dataset strategy](datasets.md) for details.

**Workbench integration:**

The **Imported Recording Workbench (experimental)** provides:

- Import UI with directory browser
- Recording list with metadata and labels
- Per-recording feature extraction and classification
- Dataset-level evaluation with accuracy, precision/recall and confusion matrices
- Feature distribution analysis across the dataset

See [HumBugDB Evaluation Baseline](evaluation-baseline.md) for usage instructions and output format.

---

## Classification and benchmarking

The plugin includes baseline classification and evaluation infrastructure:

**RuleBasedWingbeatClassifier:**

- Transparent rule-based classifier using fixed frequency thresholds from published literature
- Not a trained model — intended as a reproducible baseline
- Classifies recordings as: `female-likely`, `male-likely`, `possibly-blood-fed-female`,
  `mosquito-like`, `unknown`
- Based on dominant wingbeat frequency and harmonic analysis

**Evaluation metrics:**

- Overall accuracy
- Per-label precision and recall
- Confusion matrix (ground-truth vs predicted)
- Feature distribution statistics (dominant frequency, SNR, harmonic ratios)

**Localization metrics:**

- Position error (mean, median, 95th percentile)
- Velocity error
- Frequency stability (variance over time)
- Tracking continuity (identity persistence, track switching frequency)
- Processing latency and real-time budget compliance

See [Evaluation metrics](research/evaluation-metrics.md) for detailed definitions.

---

## Synthetic vs real comparison

**Experimental**: The plugin supports comparing feature distributions between synthetic scenarios
and real imported recordings:

- Run `SimulationScenarios` to generate synthetic data
- Import real recordings via `HumBugDbImporter`
- Use `DatasetWingbeatEvaluationWorkflow` to extract features from both
- Compare distribution statistics to validate synthetic realism

Higher standard deviation in real recordings vs synthetic indicates natural variation not yet
captured by the simulator. This workflow guides generator calibration.

---

## Generator calibration

**Experimental**: The `simulation.calibration` package provides utilities for:

- Calibrating synthetic emitter parameters to match real recording statistics
- Tuning noise levels, reflection gains and frequency distributions
- Validating synthetic data realism against imported datasets

This is future work for improving synthetic training data quality.

---

## Practical microphone setup

- Use a synchronized multi-channel interface when possible — this is the **currently supported**
  hardware path. A research-grade low-cost alternative using a set of stereo USB microphones with
  known local baselines plus an ultrasonic reference beacon for inter-device offset, drift and
  cycle-slip estimation is described in
  [Physics and latency limits](physics-and-latency-limits.md#independent-usb-microphones-and-ultrasonic-reference-beacon-calibration);
  it is an **experimental proposal that requires external processing and is not implemented in the
  current plugin**.
- Start with 2D arrays on a rigid frame and known coordinates in meters.
- Keep microphone spacing large enough for measurable delay but below room-reflection dominance.
- Record calibration clicks or chirps to estimate channel polarity, gain and sample offsets.

## Synchronization requirements

TDOA assumes one sample clock across channels. A one-sample error at 48 kHz is roughly 7.1 mm of
path difference in air, so clock drift and buffering jitter quickly dominate small arrays.

For long recordings, sample-clock drift must be measured or bounded. The current `SampleClock`
stores nominal timestamps only; it does not compensate for drift, USB buffering jitter or
per-channel latency. Real microphone rigs should capture calibration impulses before and after the
experiment and reject data when drift exceeds the localization error budget.

See [Physics and latency limits](physics-and-latency-limits.md) for the hard physical limits,
consumer-hardware constraints, ultrasonic reference-beacon calibration, calibration-reducible errors
and AR-display implications behind these requirements.

## DSP concepts

- **STFT / frequency analysis:** inspect short windows to find narrow-band wingbeat energy.
- **Harmonic detection:** insects often create harmonics; experiments should track fundamental and
  harmonics independently instead of hardcoding a species range.
- **Frequency tracking:** `WingbeatFrequencyTracker` finds a dominant peak in a configurable band.
- **Multiple insects:** frequency separation is only a first heuristic. Two insects with overlapping
  fundamentals or harmonics require multi-target tracking that is not implemented here.
- **Cross-correlation:** robust for clean delayed copies but weak under reflections and multi-source
  mixtures.
- **GCC-PHAT:** `GccPhatTdoaEstimator` uses a dependency-free frequency-domain implementation with
  PHAT weighting. It still reports integer-sample delays only and does not perform sub-sample peak
  interpolation.
- **Beamforming:** `DelayAndSumBeamformer` scores candidate positions and returns a heatmap.

## Room acoustics considerations

Reflections, standing waves, air absorption, microphone frequency response and fan noise can be
larger than the target signal. The simulator includes configurable reflection gain and noise so
algorithms can be validated before real recordings are available.

## Simulation

`SimulatedMicrophoneArraySource` generates timestamped multi-channel `AudioBlock` values from:

- `Room2D` dimensions, reflection gain and noise;
- one or more `SoundEmitter2D` instances with position, velocity, frequency and amplitude;
- a deterministic random seed for repeatable tests.

Use it to evaluate localization precision, robustness and multi-source separation before collecting
real insect recordings.

## Visualization outputs

`AcousticLocalizationSnapshot` and `AcousticDebugFrame` expose:

- tracked frequency;
- TDOA estimates and path-difference constraints;
- beamforming heatmap points;
- estimated source position.

They are UI-agnostic and can drive Swing panels, web dashboards or offline notebooks.

## Limitations and non-goals

- No species classifier or production mosquito tracker is implemented.
- No guaranteed exact AR overlay is implied; display-time predictions remain model-dependent.
- 2D geometry is supported first; 3D arrays are future work.
- GCC-PHAT, TDOA and beamforming are tested on synthetic delayed/noisy signals but remain
  experimental. Reflections, microphone mismatch, non-point sources and multiple insects can create
  false peaks.
- The pipeline exposes configurable reference-channel frequency tracking and optional multi-channel
  aggregation. It does not decide automatically which insect a frequency peak belongs to.
- No GPU, distributed processing or real-time scheduler integration is included.
- No Python bridge is added; future interoperability should remain behind stable interfaces.
- Uncertainty should be surfaced explicitly in debugging/calibration workflows and should not be
  hidden behind false point precision when latency or tracking confidence is poor.

## Package boundaries

- Stable reusable infrastructure lives under `org.hammer.audio.core`,
  `org.hammer.audio.acquisition`, `org.hammer.audio.geometry`, `org.hammer.audio.dsp` and
  `org.hammer.audio.analysis`.
- Experimental mosquito/insect localization logic lives under
  `org.hammer.audio.experimental.acoustic`.
- UI and Swing code live in `org.hammer.audio.ui` and `org.hammer`.
- `ArchitectureBoundaryTest` enforces that stable audio packages do not import
  `org.hammer.audio.experimental.*`, and that DSP/acquisition/geometry do not depend on UI/app
  packages.

## Future research directions

- Multi-source separation using harmonic grouping and probabilistic frequency tracks.
- Sub-sample GCC-PHAT interpolation and confidence calibration from real recordings.
- 3D geometry and calibrated array files.
- Better reflection models and measured room impulse responses.
- Benchmark corpus with real and synthetic mosquito-like recordings.

See the [`research/`](research/README.md) folder for the paper outline,
reproducible experiments, evaluation metrics, demo scenarios, hardware setup
notes and JSON simulation datasets.
