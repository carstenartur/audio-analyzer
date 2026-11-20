# HumBugDB Evaluation Baseline

This document describes how to run reproducible classification evaluation using imported
HumBugDB recordings.

**Status: Implemented**

The HumBugDB importer, feature extraction, classification baseline and evaluation metrics are
fully operational and accessible via the **Imported Recording Workbench (experimental)** panel
or programmatically via `DatasetWingbeatEvaluationWorkflow`.

## Prerequisites

- Java 21+, Maven (or the repository wrapper), and a graphical desktop
- A local copy of the HumBugDB export (see below)

## Obtaining the Dataset

HumBugDB is available from the [HumBug Mosquito project](https://github.com/HumBug-Mosquito/HumBugDB)
and the linked Zenodo release. Keep the exact release identifier and its dataset licence with your
results; the code repository's licence does not by itself establish the licence of every dataset export.

After downloading and extracting, you should have a directory structure similar to:

```
humbugdb-root/
  data/
    audio/        ← WAV clip files
    metadata/     ← CSV metadata files (e.g. subset.csv)
```

## Running the Evaluation

### From the Workbench (GUI)

**Status: Implemented**

Build from the repository root and explicitly select the desktop artifact. The same build also creates
a `*-workbench.jar` for the browser host, so a glob matching both is not a desktop launch command:

```bash
./mvnw clean package
DESKTOP_JAR=$(find audio-app/target -maxdepth 1 -type f \
  -name 'audio-app-*.jar' ! -name '*-workbench.jar' -print -quit)
java -jar "$DESKTOP_JAR"
```

On Windows use `mvnw.cmd` and launch the unclassified `audio-app-<version>.jar`; see
[Getting started](../../getting-started.md).

1. Launch the desktop application using the command above.
2. Open **Plugins > Experimental Acoustic Localization > Open: Imported Recording Workbench (experimental)**
3. Click **Browse…** and navigate to your local `humbugdb-root` directory.
4. Click **Import** to load the dataset into a `DatasetManifest`.
5. Wait for the import and analysis to finish. Inspect the manifest, select a **Recording**, and click
   **Replay analysis** for its features and baseline classifier output.
6. Choose a **Calibration scope**, then **Run calibration** when comparing the synthetic generator
   with the imported feature distribution. This is generator calibration, not array timing calibration.

After import, the workbench displays six report areas:

|         Panel         |                                           Content                                           |
|-----------------------|---------------------------------------------------------------------------------------------|
| Manifest              | Recording list with duration, sample rate and labels                                        |
| Analytics             | Dataset analytics: recording count, label distribution, sample-rate and duration statistics |
| Recording inspection  | Per-recording feature extraction and classifier output                                      |
| Evaluation summary    | Accuracy, per-label precision/recall and confusion matrix                                   |
| Feature histograms    | Distributions of the extracted recording features                                           |
| Generator calibration | Before/after feature comparisons for the selected calibration scope                         |

An import error is shown in the manifest area. Check that the chosen directory is the export root,
the metadata CSVs exist, and the referenced local WAV files are available. This panel does not download
missing data or import experiment `.aarec` recordings. Preserve the dataset version, labels and input
files to repeat an evaluation. The code route below exposes report strings for saving alongside them.

### From Code

```java
Path humbugRoot = Path.of("/path/to/humbugdb-root");
DatasetManifest manifest = new HumBugDbImporter().importFrom(humbugRoot);

// Dataset analytics
DatasetAnalytics analytics = DatasetAnalytics.compute(manifest);
System.out.println(analytics.toMarkdownReport());

// Classification baseline
DatasetWingbeatEvaluationWorkflow workflow = new DatasetWingbeatEvaluationWorkflow();
WingbeatDataset.Evaluation evaluation =
    workflow.evaluate(manifest, new RuleBasedWingbeatClassifier());
System.out.println(DatasetWingbeatEvaluationWorkflow.toMarkdownReport(evaluation));

// Feature distribution (all recordings)
List<DatasetWingbeatEvaluationWorkflow.RecordingAnalysis> analyses =
    workflow.analyzeAll(manifest, new RuleBasedWingbeatClassifier());
System.out.println(DatasetWingbeatEvaluationWorkflow.toFeatureDistributionMarkdown(analyses));
```

## Understanding the Output

### Dataset Analytics

`DatasetAnalytics.compute(manifest).toMarkdownReport()` produces:

- **Recording count** — total clips in the manifest.
- **Duration distribution** — min, max, mean, std dev of clip durations in seconds.
- **Sample-rate distribution** — count of recordings at each observed sample rate.
- **Label distribution** — for each metadata key (`species`, `gender`, `fed`, etc.), the count of
  recordings per unique value.

### Classification Baseline

`DatasetWingbeatEvaluationWorkflow.toMarkdownReport(evaluation)` produces:

- **Overall accuracy** — proportion of recordings correctly classified.
- **Per-label statistics table** — for each ground-truth label:
  - Sample count
  - Correct count
  - **Recall** — fraction of actual positives correctly identified
  - **Precision** — fraction of predicted positives that were truly positive
- **Confusion matrix** — rows are ground-truth labels, columns are predicted labels.

The ground-truth labels are derived from the HumBugDB `gender` and `fed` metadata columns using
the following mapping:

|               HumBugDB metadata               |     Ground-truth label      |
|-----------------------------------------------|-----------------------------|
| `gender=female, fed=yes`                      | `possibly-blood-fed-female` |
| `gender=female`                               | `female-likely`             |
| `gender=male`                                 | `male-likely`               |
| `sound_type` contains `background` or `noise` | `unknown`                   |
| Species present but no gender                 | `mosquito-like`             |
| Otherwise                                     | `unknown`                   |

### Feature Distribution

`DatasetWingbeatEvaluationWorkflow.toFeatureDistributionMarkdown(analyses)` produces summary
statistics for:

- **Dominant frequency (Hz)** — estimated fundamental wingbeat frequency per recording.
- **Signal-to-noise ratio** — ratio of peak magnitude to background noise floor.
- **2nd harmonic / fundamental ratio** — harmonic strength indicator (when available).

## Interpreting Results

The `RuleBasedWingbeatClassifier` uses fixed frequency thresholds derived from published literature.
It is a transparent, reproducible baseline — not a trained model. Expect:

- High recall for `female-likely` and `male-likely` when recordings are clean, single-insect clips
  in the mosquito frequency range (300–800 Hz).
- Lower precision when multiple labels overlap near threshold boundaries.
- `unknown` predictions for out-of-band frequencies or noisy clips.

Use the per-label precision/recall table and confusion matrix to identify which labels the heuristic
handles well and where training data would add most value.

## Synthetic vs Real Comparison

To compare synthetic scenario results with real HumBugDB recordings, run the feature distribution
analysis on both and compare the statistics tables:

- Synthetic scenarios use `SimulationScenarios` (e.g. `twoMosquitoWingbeats()`).
- Real recordings use `DatasetWingbeatEvaluationWorkflow.analyzeAll()` on the imported manifest.

A higher standard deviation of dominant frequency in real recordings vs. synthetic scenarios
indicates natural variation that synthetic models do not yet capture.
