# Experiment Node Catalog

**Issue**: [#215](https://github.com/carstenartur/audio-analyzer/issues/215)  
**Authority**: Catalog specification (domain layer)  
**Code**: `org.hammer.audio.workflow.catalog.ExperimentNodeCatalog`

---

## Overview

This document defines the first experiment node catalog for the Experiment Modeling Workbench.
Every node maps to the existing `audio-core` workflow model using `Node`, `Port`, `DataType` and
`PortDirection`. No new domain types are required.

Port data types use the constants from `DataTypes`:

|             Constant              |      Type string       |
|-----------------------------------|------------------------|
| `DataTypes.DATASET`               | `Dataset`              |
| `DataTypes.AUDIO_BLOCK`           | `AudioBlock`           |
| `DataTypes.SPECTRUM`              | `Spectrum`             |
| `DataTypes.FEATURE_SET`           | `FeatureSet`           |
| `DataTypes.CLASSIFICATION_RESULT` | `ClassificationResult` |
| `DataTypes.LOCALIZATION_RESULT`   | `LocalizationResult`   |
| `DataTypes.BENCHMARK_RESULT`      | `BenchmarkResult`      |
| `DataTypes.REPORT`                | `Report`               |

---

## Input nodes

### RecordingInput

|    Port     | Direction |   Type    | Required | Multiplicity |
|-------------|-----------|-----------|----------|--------------|
| `audio-out` | OUTPUT    | `Dataset` | false    | SINGLE       |

### SyntheticSignalGenerator

|     Port     | Direction |     Type     | Required | Multiplicity |
|--------------|-----------|--------------|----------|--------------|
| `signal-out` | OUTPUT    | `AudioBlock` | false    | SINGLE       |

### HumBugDbImport

|     Port      | Direction |   Type    | Required | Multiplicity |
|---------------|-----------|-----------|----------|--------------|
| `dataset-out` | OUTPUT    | `Dataset` | false    | SINGLE       |

---

## DSP nodes

### Gain

|    Port     | Direction |     Type     | Required | Multiplicity |
|-------------|-----------|--------------|----------|--------------|
| `audio-in`  | INPUT     | `AudioBlock` | true     | SINGLE       |
| `audio-out` | OUTPUT    | `AudioBlock` | false    | SINGLE       |

### BandpassFilter

|    Port     | Direction |     Type     | Required | Multiplicity |
|-------------|-----------|--------------|----------|--------------|
| `audio-in`  | INPUT     | `AudioBlock` | true     | SINGLE       |
| `audio-out` | OUTPUT    | `AudioBlock` | false    | SINGLE       |

### FFT

|      Port      | Direction |     Type     | Required | Multiplicity |
|----------------|-----------|--------------|----------|--------------|
| `audio-in`     | INPUT     | `AudioBlock` | true     | SINGLE       |
| `spectrum-out` | OUTPUT    | `Spectrum`   | false    | SINGLE       |

---

## Analysis nodes

### WingbeatFeatureExtraction

|      Port      | Direction |     Type     | Required | Multiplicity |
|----------------|-----------|--------------|----------|--------------|
| `spectrum-in`  | INPUT     | `Spectrum`   | true     | SINGLE       |
| `features-out` | OUTPUT    | `FeatureSet` | false    | SINGLE       |

### Classifier

|     Port      | Direction |          Type          | Required | Multiplicity |
|---------------|-----------|------------------------|----------|--------------|
| `features-in` | INPUT     | `FeatureSet`           | true     | SINGLE       |
| `result-out`  | OUTPUT    | `ClassificationResult` | false    | SINGLE       |

### Localization

|      Port      | Direction |         Type         | Required | Multiplicity |
|----------------|-----------|----------------------|----------|--------------|
| `audio-in`     | INPUT     | `AudioBlock`         | true     | SINGLE       |
| `location-out` | OUTPUT    | `LocalizationResult` | false    | SINGLE       |

### Benchmark

|      Port       | Direction |          Type          | Required | Multiplicity |
|-----------------|-----------|------------------------|----------|--------------|
| `result-in`     | INPUT     | `ClassificationResult` | true     | SINGLE       |
| `benchmark-out` | OUTPUT    | `BenchmarkResult`      | false    | SINGLE       |

---

## Output nodes

### Report

|      Port      | Direction |       Type        | Required | Multiplicity |
|----------------|-----------|-------------------|----------|--------------|
| `benchmark-in` | INPUT     | `BenchmarkResult` | true     | SINGLE       |
| `report-out`   | OUTPUT    | `Report`          | false    | SINGLE       |

### EvidenceExport

|    Port     | Direction |          Type          | Required | Multiplicity |
|-------------|-----------|------------------------|----------|--------------|
| `result-in` | INPUT     | `ClassificationResult` | true     | SINGLE       |

*(No output port — terminal sink.)*

---

## Valid connection examples

| # |           Source            |   Source port   |           Target            |  Target port   |     Matching type      |
|---|-----------------------------|-----------------|-----------------------------|----------------|------------------------|
| 1 | `SyntheticSignalGenerator`  | `signal-out`    | `Gain`                      | `audio-in`     | `AudioBlock`           |
| 2 | `Gain`                      | `audio-out`     | `BandpassFilter`            | `audio-in`     | `AudioBlock`           |
| 3 | `BandpassFilter`            | `audio-out`     | `FFT`                       | `audio-in`     | `AudioBlock`           |
| 4 | `FFT`                       | `spectrum-out`  | `WingbeatFeatureExtraction` | `spectrum-in`  | `Spectrum`             |
| 5 | `WingbeatFeatureExtraction` | `features-out`  | `Classifier`                | `features-in`  | `FeatureSet`           |
| 6 | `Classifier`                | `result-out`    | `Benchmark`                 | `result-in`    | `ClassificationResult` |
| 7 | `Benchmark`                 | `benchmark-out` | `Report`                    | `benchmark-in` | `BenchmarkResult`      |
| 8 | `SyntheticSignalGenerator`  | `signal-out`    | `Localization`              | `audio-in`     | `AudioBlock`           |
| 9 | `Classifier`                | `result-out`    | `EvidenceExport`            | `result-in`    | `ClassificationResult` |

> **Note on dataset sources**: `RecordingInput` and `HumBugDbImport` emit `Dataset`, not
> `AudioBlock`. The current first-slice catalog intentionally has no `Dataset` → `AudioBlock`
> adapter node yet, so dataset sources do not have a valid downstream connection in this catalog.

---

## Invalid connection examples

| # |           Source            |  Source port   |     Target     |  Target port   |      Source type       |      Target type       |        Reason         |
|---|-----------------------------|----------------|----------------|----------------|------------------------|------------------------|-----------------------|
| 1 | `RecordingInput`            | `audio-out`    | `Gain`         | `audio-in`     | `Dataset`              | `AudioBlock`           | Type mismatch         |
| 2 | `FFT`                       | `spectrum-out` | `Classifier`   | `features-in`  | `Spectrum`             | `FeatureSet`           | Type mismatch         |
| 3 | `Classifier`                | `result-out`   | `Report`       | `benchmark-in` | `ClassificationResult` | `BenchmarkResult`      | Type mismatch         |
| 4 | `Gain`                      | `audio-out`    | `Benchmark`    | `result-in`    | `AudioBlock`           | `ClassificationResult` | Type mismatch         |
| 5 | `WingbeatFeatureExtraction` | `features-out` | `Localization` | `audio-in`     | `FeatureSet`           | `AudioBlock`           | Type mismatch         |
| 6 | `HumBugDbImport`            | `dataset-out`  | `FFT`          | `audio-in`     | `Dataset`              | `AudioBlock`           | Type mismatch         |
| 7 | `Report`                    | `report-out`   | `Gain`         | `audio-in`     | `Report`               | `AudioBlock`           | Wrong direction chain |

---

## Minimal valid report workflow example

```text
SyntheticSignalGenerator (signal-out: AudioBlock)
    -> FFT                       (audio-in / spectrum-out: AudioBlock → Spectrum)
        -> WingbeatFeatureExtraction (spectrum-in / features-out: Spectrum → FeatureSet)
            -> Classifier            (features-in / result-out: FeatureSet → ClassificationResult)
                -> Benchmark         (result-in / benchmark-out: ClassificationResult → BenchmarkResult)
                    -> Report        (benchmark-in: BenchmarkResult)
```

> A shorter valid terminal chain is `Classifier -> EvidenceExport`. `Report` always requires a
> `BenchmarkResult`, so a `Benchmark` node must precede it.

