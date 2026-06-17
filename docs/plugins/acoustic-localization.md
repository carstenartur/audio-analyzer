# Experimental Acoustic Localization

This is the entry page for the optional acoustic-localization plugin. The plugin is a research/demo
extension for microphone-array experiments with weak, intermittent or insect-like sound sources. It
is **not** a production mosquito detector, species classifier or validated tracking product.

## Status at a glance

|     Property     |                                          Value                                           |
|------------------|------------------------------------------------------------------------------------------|
| Module           | `audio-experimental-acoustic`                                                            |
| Plugin API       | `audio-plugin-api`                                                                       |
| Discovery        | Java `ServiceLoader` via `META-INF/services/org.hammer.audio.plugin.AudioAnalyzerPlugin` |
| Host integration | `audio-app` depends on the concrete plugin at runtime only                               |
| Stability        | Experimental research code; stable reusable primitives stay in core modules              |

## Current capabilities

**Implemented:**
- **Interactive simulation workbench** — run any of nine deterministic scenarios through the full
  tracking pipeline directly from the Plugins menu, with live per-frame log, 2-D room map and
  Markdown / CSV / JSON export;
- **HumBugDB import workbench** — local offline-first import of HumBugDB dataset, recording
  inspection, feature extraction, rule-based classification and dataset-level evaluation;
- **End-to-end tracking pipeline** — multi-peak detection, frequency clustering, TDOA estimation,
  beamforming, Kalman-based source tracking with identity persistence;
- **Synthetic signal generation** — deterministic room acoustics simulation with moving emitters,
  reflections, noise and Doppler;
- **Benchmark infrastructure** — localization metrics, classification evaluation, confusion matrices
  and feature distribution analysis;
- Plugin descriptor, menu/view contributions and generic host integration;
- Cross-correlation and GCC-PHAT TDOA estimators;
- Delay-and-sum beamforming over 2D candidate grids;
- Doppler radial-velocity estimation and multi-sensor velocity reconstruction.

**Experimental:**
- Feature ranking and comparison workflows for classifier development;
- Synthetic-vs-real signal distribution comparison;
- Generator calibration for realistic synthetic data.

**Known limitations:**
- No guaranteed mosquito detection or species classification;
- No guaranteed exact AR overlay; any display-time prediction is model-dependent;
- Synthetic validation does not prove real-world reliability;
- Reflections, noise, microphone mismatch and multiple weak sources can dominate results;
- Accurate TDOA requires calibrated geometry and a shared sample clock across channels;
- `SampleClock` currently records nominal timing only and does not compensate drift or USB buffering
  jitter;
- Sub-sample TDOA interpolation not implemented;
- 3D geometry and multi-story array configurations are future work.

## Architecture overview

The plugin implements a full real-time tracking pipeline:

```text
AudioBlock (multi-channel synchronized frame)
  → MultiPeakDetector (FFT + parabolic refinement)
  → FrequencyClusterer (cross-channel grouping)
  → TdoaEstimator (GCC-PHAT or cross-correlation)
  → DelayAndSumBeamformer (2D candidate grid scoring)
  → SourceTracker (Kalman smoothing + identity persistence)
  → TrackingSnapshot (immutable per-frame output)
```

The pipeline is deterministic, allocation-free per frame, and respects a configurable real-time
budget. See [Tracking pipeline](acoustic-localization/tracking.md) for details.

## Workbench usage

### Simulation workbench

Run **Plugins > Experimental Acoustic Localization > Open: Acoustic Localization Workbench
(experimental)** to:

1. Select a scenario from nine deterministic presets (single source, moving source, two sources,
   reflections, noise, etc.)
2. Configure pipeline parameters (FFT size, frequency band, TDOA estimator)
3. Run the scenario and view live per-frame logs
4. Inspect the 2D room map with microphones, ground truth and tracked positions
5. Export results as Markdown, CSV or JSON-lines

All scenarios use deterministic synthetic data for reproducibility. See
[How to run the workbench](acoustic-localization/README.md#how-to-run-the-workbench) for details.

### HumBugDB import workbench

Run **Plugins > Experimental Acoustic Localization > Open: Imported Recording Workbench
(experimental)** to:

1. Import a local HumBugDB export directory (offline-first, no automatic downloads)
2. Browse imported recordings with metadata and labels
3. Re-run feature extraction and rule-based classification on individual clips
4. Review dataset-level evaluation metrics (accuracy, precision/recall, confusion matrix)
5. Inspect feature distributions across the dataset

See [HumBugDB import](acoustic-localization/datasets.md#humbugdb-importer-implementation) and
[Evaluation baseline](acoustic-localization/evaluation-baseline.md) for details.

## Synthetic signal generation

The `audio-experimental-acoustic` module provides a deterministic room-acoustics simulator:

- **Room2D** — configurable dimensions, reflection gain and broadband noise
- **SoundEmitter2D** — position, velocity, frequency and amplitude
- **SimulatedMicrophoneArraySource** — timestamped multi-channel AudioBlock generation

Nine validation scenarios are provided in `SimulationScenarios`: single source, moving source, two
close frequencies, noisy room, reflections, Doppler experiments and wingbeat pairs. All scenarios
are covered by `TrackingPipelineScenarioTest`.

## Ground truth and benchmarking

The plugin includes benchmark infrastructure for:

- **Localization metrics** — position error, velocity error, frequency stability, tracking
  continuity (see [Evaluation metrics](acoustic-localization/research/evaluation-metrics.md))
- **Classification metrics** — accuracy, per-label precision/recall, confusion matrices
- **Feature analysis** — dominant frequency, SNR, harmonic ratios, distribution statistics

Benchmark results are exported as Markdown reports with tables and summary statistics.

## HumBugDB import

The `HumBugDbImporter` provides local offline import of HumBugDB datasets:

1. User provides absolute path to local HumBugDB export root
2. Importer reads metadata CSVs and resolves WAV file paths
3. Normalized `DatasetManifest` is created with recordings, labels and annotations
4. Manifest can be used for classification evaluation and feature distribution analysis

No automatic downloads. Users must obtain HumBugDB from the upstream project and accept its license
terms before using it. See [Real-world dataset strategy](acoustic-localization/datasets.md).

## Dataset evaluation

The `DatasetWingbeatEvaluationWorkflow` provides:

- **Dataset analytics** — recording count, duration/sample-rate distributions, label distributions
- **Classification baseline** — rule-based classifier evaluation with precision/recall per label
- **Feature distribution** — dominant frequency, SNR and harmonic ratio statistics across recordings
- **Confusion matrix** — ground-truth vs predicted label comparison

Evaluation reports are generated as Markdown tables. The rule-based classifier uses fixed frequency
thresholds from published literature as a transparent baseline (not a trained model).

## Feature evaluation and ranking

**Experimental**: The plugin includes `FeatureRanker` and `FeatureComparison` classes for:

- Ranking features by discriminative power for classification
- Comparing feature distributions across labels
- Identifying redundant or low-information features

This infrastructure supports future classifier development but is not yet integrated into the
workbench UI.

## Synthetic-vs-real comparison

**Experimental**: The plugin supports comparing feature distributions between:

- Synthetic scenarios from `SimulationScenarios`
- Real recordings from imported datasets

This helps validate whether synthetic data captures the statistical properties of real recordings.
Standard deviation differences indicate natural variation not yet modeled.

## Generator calibration

**Experimental**: The `simulation.calibration` package provides utilities for:

- Calibrating synthetic emitter parameters to match real recording statistics
- Tuning noise levels, reflection gains and frequency distributions
- Validating synthetic data realism against imported datasets

This is future work for improving synthetic training data quality.

## Remaining limitations

- **No production-ready detector**: The plugin is research code, not a validated product
- **No species classifier**: The rule-based classifier is a baseline; no trained model is included
- **No AR overlay guarantee**: Display-time predictions are model-dependent
- **Synthetic-only validation**: Real-world reliability is not proven
- **2D only**: 3D arrays and multi-story configurations are future work
- **Integer-sample TDOA**: Sub-sample interpolation not implemented
- **Nominal timing**: SampleClock does not compensate drift or jitter
- **No GPU acceleration**: All computation is single-threaded CPU

## Roadmap

The roadmap for acoustic-localization research is tracked in repository issues. Current open work
includes:

1. Sub-sample GCC-PHAT peak interpolation
2. Multi-source separation using probabilistic data association
3. 3D geometry and calibrated array file formats
4. Improved reflection models and room impulse responses
5. Expanded benchmark corpus with more real recordings

See the main [Roadmap](../../ROADMAP.md#experimental-acoustic-localization) for details.

## Detailed documentation

- [**How to run the workbench**](acoustic-localization/README.md#how-to-run-the-workbench)
- [Plugin details, pipeline and boundaries](acoustic-localization/README.md)
- [Physics and latency limits](acoustic-localization/physics-and-latency-limits.md)
- [Synchronization requirements](acoustic-localization/synchronization.md)
- [Tracking pipeline](acoustic-localization/tracking.md)
- [Real-world dataset strategy](acoustic-localization/datasets.md)
- [HumBugDB evaluation baseline](acoustic-localization/evaluation-baseline.md)
- [Research notes and datasets](acoustic-localization/research/README.md)

