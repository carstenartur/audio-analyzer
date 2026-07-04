# Audio Analyzer

[![Java CI with Maven](https://github.com/carstenartur/audio-analyzer/actions/workflows/maven.yml/badge.svg?branch=master)](https://github.com/carstenartur/audio-analyzer/actions/workflows/maven.yml)
[![Tests](https://img.shields.io/endpoint?url=https://carstenartur.github.io/audio-analyzer/tests/badge.json)](https://carstenartur.github.io/audio-analyzer/tests/surefire-report.html)
[![Coverage](https://img.shields.io/endpoint?url=https://carstenartur.github.io/audio-analyzer/coverage/badge.json)](https://carstenartur.github.io/audio-analyzer/coverage/)
[![CodeQL](https://github.com/carstenartur/audio-analyzer/actions/workflows/codeql.yml/badge.svg?branch=master)](https://github.com/carstenartur/audio-analyzer/actions/workflows/codeql.yml)
[![License](https://img.shields.io/github/license/carstenartur/audio-analyzer)](LICENSE)
[![DOI](https://zenodo.org/badge/7397122.svg)](https://doi.org/10.5281/zenodo.21186367)
[![SBOM](https://img.shields.io/badge/SBOM-CycloneDX-informational?logo=owasp)](https://github.com/carstenartur/audio-analyzer/dependency-graph/sbom)

**Audio Analyzer** is a Java 21 / Swing workbench for reproducible audio analysis, DSP experiments
and acoustic-localization research. It combines a desktop dashboard, deterministic signal pipelines,
recording/replay tooling, evidence export and an optional experimental plugin for microphone-array
localization.

The repository is useful for algorithm development, measurement experiments, UI prototyping and
research documentation. It is **not** a validated production detector for mosquitoes, species
classification or safety-critical tracking.

## Current status

Stable application and library foundations:

- immutable audio-domain models, ring buffer and deterministic signal generators;
- sample decoding, FFT spectrum, measurements, spectrogram and diagnosis analyzers;
- Swing dashboard panels for waveform, phase, spectrum, spectrogram, measurements and diagnosis;
- `.aar` recording/replay, evidence export and Markdown A/B comparison reports;
- Maven, Spotless, unit tests, architecture fitness tests, JaCoCo, Checkstyle, PMD, SpotBugs and
  CodeQL integration.

Experimental research areas:

- acoustic-localization plugin with simulation and imported-recording workbenches;
- multi-peak detection, frequency clustering, TDOA estimation, beamforming and Kalman tracking;
- HumBugDB import, wingbeat feature extraction and rule-based classification baseline;
- synthetic-vs-real comparison, generator calibration and localization benchmark metrics.

## Quickstart

Requirements:

- Java 21 or newer;
- a POSIX shell for the commands below, or `mvnw.cmd` on Windows.

```bash
# Build, test and run all configured quality gates
./mvnw clean verify

# Run the packaged Swing application
java -jar audio-app/target/audio-app-*.jar

# Regenerate README and feature screenshots from compiled classes
java -cp "audio-app/target/classes:audio-app/target/lib/*" \
  org.hammer.tools.DocImageRenderer docs/images

# Optional JMH benchmarks
./mvnw -pl audio-dsp -Pjmh package
```

On Windows, replace `./mvnw` with `mvnw.cmd`, use `;` instead of `:` in classpaths and substitute the
concrete built JAR name if the shell does not expand `audio-app/target/audio-app-*.jar`.

## Dashboard screenshot

![Audio Analyzer dashboard showing a reproducible 440 Hz sine demo](docs/images/screenshot.png)

The screenshot is generated headlessly by `DocImageRenderer`. It is intended as release evidence as
well as documentation, so it should be regenerated and visually reviewed whenever the dashboard layout
changes.

## Main workflows

- **Inspect audio live or from deterministic demo sources.** Use waveform, phase, spectrum,
  spectrogram, measurement and diagnosis panels to inspect signal behavior.
- **Stabilize periodic waveforms.** Enable oscilloscope-style triggering to lock a repeating signal to
  a readable position.
- **Analyze spectra over time.** Use exponential averaging and peak hold to separate steady tones from
  intermittent transients.
- **Record and replay sessions.** Capture `.aar` recordings and replay them through the same analysis
  pipeline for reproducible debugging.
- **Export evidence.** Create CSV/PNG/evidence bundles and Markdown A/B comparisons for regression
  notes or QA tickets.
- **Run experimental localization scenarios.** The optional acoustic plugin provides deterministic
  microphone-array simulations, dataset import and benchmarking. Treat those workflows as research
  tools unless synchronized hardware and calibration evidence are available.

## Documentation map

Start here:

- [Architecture](ARCHITECTURE.md) — module boundaries, package structure and plugin/workbench design.
- [Development](docs/development.md) — build, tests, CI, screenshot generation and contribution notes.
- [Quality gates and coverage](docs/quality.md) — what fails the build and what remains baseline debt.
- [Feature guides](docs/features/README.md) — user-facing dashboard, recording and comparison features.
- [Experimental acoustic localization](docs/plugins/acoustic-localization.md) — plugin overview,
  capabilities and limitations.
- [Roadmap](ROADMAP.md) — open next steps and research directions.
- [QA findings](docs/QA-FINDINGS.md) — current product-hardening risks and follow-up actions.
- [Release QA checklists](docs/qa/README.md) — manual QA, screenshot QA and release readiness.

## Module overview

```text
audio-core                  immutable audio-domain types, snapshots and ring buffer
audio-geometry              reusable 2D geometry and localization constraints
audio-acquisition           microphone metadata, arrays, multichannel sources and clocks
audio-dsp                   FFT, DSP pipelines, analyzers, diagnosis and recording format
audio-plugin-api            stable host-facing plugin contracts
audio-experimental-acoustic optional research plugin for localization and datasets
audio-app                   Swing UI, JavaSound/demo wiring, export and plugin host
```

The application should depend on stable contracts and load experimental plugins through the plugin
host. Production-ready primitives belong in the stable modules; research code belongs in
`audio-experimental-acoustic` until it has stable API, tests and documentation.

## Quality expectations

The repository treats formatting, tests and architecture checks as part of the product:

- `./mvnw clean verify` is the default validation command;
- Spotless formats Java, POM and Markdown files;
- static analysis is baseline-gated in CI;
- generated screenshots are tracked and should be regenerated from the release candidate;
- public documentation must not make stronger claims than the implemented tests and QA evidence
  support.

## Important limitations

- Acoustic localization is currently experimental and mostly validated through deterministic synthetic
  scenarios.
- Real microphone-array localization requires synchronized channels, calibrated geometry and timing
  error budgets.
- The HumBugDB workflows operate on local dataset exports; the project does not automatically download
  third-party datasets.
- The rule-based wingbeat classifier is a transparent baseline, not a trained species classifier.
- Documentation screenshots are generated assets and still require visual QA before public release.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).
