# Acoustic mosquito datasets strategy

This document defines how real-world wingbeat datasets should be integrated into
`audio-experimental-acoustic` without committing large files to Git.

It complements:

- wingbeat classification work (`carstenartur/audio-analyzer#143`),
- synthetic deterministic test data (`carstenartur/audio-analyzer#144`),
- scenario benchmarking/reporting (`carstenartur/audio-analyzer#137`).

## Integration principles

- Keep imports optional and offline-friendly: users provide a local dataset directory.
- Do not auto-download restricted data.
- Keep license and usage constraints visible in metadata and manifests.
- Keep deterministic tests synthetic by default; real datasets are for opt-in local benchmarks.
- Avoid hard-coding to one source layout (HumBugDB is first candidate, not the only one).

## Candidate dataset survey

Legend:

- ✅ suitable now
- ⚠️ partly suitable / manual checks required
- ❌ not suitable for automated integration in this repository

|                           Dataset                           |                                         Source URL / publication                                          |                              Availability                               |                                          License / constraints                                          |                 Audio format                 |                                     Annotation format                                     |                Species labels                |          Sex labels           |          Age labels           |   Feeding/blood-meal labels   |          Environment/background metadata          |                     #143 classification suitability                     |           #137 benchmark suitability            |         #144 synthetic-vs-real suitability         |                          Notes                           |
|-------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|----------------------------------------------|-------------------------------------------------------------------------------------------|----------------------------------------------|-------------------------------|-------------------------------|-------------------------------|---------------------------------------------------|-------------------------------------------------------------------------|-------------------------------------------------|----------------------------------------------------|----------------------------------------------------------|
| HumBugDB (large-scale acoustic mosquito dataset)            | https://github.com/HumBug-Mosquito/HumBugDB and linked Zenodo release                                     | Downloadable export (manual download/extract)                           | Code repo is MIT; dataset license must be taken from the specific dataset release/supplement before use | WAV files in local export (per project docs) | CSV metadata (`data/metadata/*.csv`) and task-specific splits                             | Yes (MSC task documents 8 species)           | Not confirmed in fetched docs | Not confirmed in fetched docs | Not confirmed in fetched docs | Present via metadata CSVs (details in supplement) | ✅ strong candidate                                                      | ✅ strong candidate for local/offline benchmarks | ✅ strong candidate to compare synthetic vs real    | Prioritized as first importer target                     |
| HumBug Zooniverse mosquito-event dataset                    | https://github.com/HumBug-Mosquito/ZooniverseData and linked arXiv paper https://arxiv.org/abs/2001.04733 | Public ZIP links provided in README (`audio_1sec`, `audio_2sec`)        | No explicit LICENSE file in repo; treat as research-use until clarified                                 | WAV (`audio_1sec` / `Zoo_segment`)           | CSV labels (e.g. `audio_1sec.csv`, `coarse_data_2sec.csv`) with `{yes,no,not_sure}` votes | No explicit species labels in baseline files | No                            | No                            | No                            | Partial source breakdown in metadata notebook     | ⚠️ useful for mosquito-event detection; weak for species classification | ✅ event-level benchmark input                   | ✅ useful as real non-synthetic event corpus        | Good second importer target after HumBugDB               |
| Kaggle wingbeat mirrors (for example `potamitis/wingbeats`) | Example: https://www.kaggle.com/datasets/potamitis/wingbeats                                              | Public page, but requires Kaggle account/terms and may change over time | Must check each Kaggle dataset license and redistribution terms separately                              | Typically short WAV clips                    | Usually CSV/folder labels (varies by uploader)                                            | Often yes                                    | Usually unknown               | Usually unknown               | Usually unknown               | Usually limited                                   | ⚠️ useful for optional local experiments                                | ⚠️ can be used only after license check         | ⚠️ useful for exploratory synthetic-vs-real checks | Do not make this a mandatory CI dependency               |
| Request-only or restricted subsets (author-provided access) | HumBugDB docs include contact path and early-access language in task docs                                 | Available on request from dataset authors                               | Terms negotiated case-by-case                                                                           | Varies                                       | Varies                                                                                    | Varies                                       | Varies                        | Varies                        | Varies                        | Varies                                            | ⚠️ potentially useful                                                   | ⚠️ manual-only                                  | ⚠️ manual-only                                     | Keep outside automated pipeline until terms are explicit |
| Non-usable entries (no raw audio or unclear redistribution) | Any source that provides only derived features/figures or no license statement                            | Not automatable                                                         | Unclear/insufficient rights                                                                             | Missing                                      | Varies                                                                                    | Varies                                       | Varies                        | Varies                        | Varies                        | Varies                                            | ❌                                                                       | ❌                                               | ❌                                                  | Track in this table, but do not integrate                |

## Normalized metadata schema

The initial schema is represented in code by:

- `org.hammer.audio.experimental.acoustic.dataset.DatasetDescriptor`
- `org.hammer.audio.experimental.acoustic.dataset.DatasetRecording`
- `org.hammer.audio.experimental.acoustic.dataset.DatasetAnnotation`
- `org.hammer.audio.experimental.acoustic.dataset.DatasetManifest`
- `org.hammer.audio.experimental.acoustic.dataset.DatasetImporter`

### DatasetDescriptor

- `id`
- `name`
- `source` (URL/publication URI)
- `license`
- `localRootPath` (absolute path provided by user)
- `metadataSchema` (field map documenting metadata semantics)

### DatasetRecording

- `recordingId`
- `audioPath`
- `sampleRateHz`
- `durationSeconds`
- `labels` (species/sex/age/feeding/etc. when available)
- `annotations` (time spans and labels)
- `metadata` (environment, device, location, capture context)

## HumBugDB importer decision and implementation plan

Decision: **implement HumBugDB importer first**, but keep it optional and local-only.

Planned steps:

1. Add `DatasetImporter` implementation for HumBugDB local exports.
2. Read metadata CSV files from a user-provided root path.
3. Map each row to `DatasetRecording` and build `DatasetManifest`.
4. Export normalized manifest (JSON/CSV) for benchmark tooling.
5. Keep fixtures tiny and synthetic unless explicit redistribution permission exists.

## Benchmark integration path

After local import is available, benchmark flow should be:

1. Real dataset import -> `DatasetManifest`
2. Feature extraction pipeline -> `WingbeatFeatureVector`
3. Classification/evaluation -> `WingbeatDataset` and benchmark reports
4. Synthetic-vs-real comparison report combining:
   - deterministic simulation scenarios,
   - real imported datasets,
   - consistent metrics from benchmark package

This keeps #143, #144 and #137 aligned without forcing large or restricted datasets into Git.
