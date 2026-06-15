# Audio Analyzer

[![Java CI with Maven](https://github.com/carstenartur/audio-analyzer/actions/workflows/maven.yml/badge.svg?branch=master)](https://github.com/carstenartur/audio-analyzer/actions/workflows/maven.yml)
[![Tests](https://img.shields.io/endpoint?url=https://carstenartur.github.io/audio-analyzer/tests/badge.json)](https://carstenartur.github.io/audio-analyzer/tests/surefire-report.html)
[![Coverage](https://img.shields.io/endpoint?url=https://carstenartur.github.io/audio-analyzer/coverage/badge.json)](https://carstenartur.github.io/audio-analyzer/coverage/)
[![CodeQL](https://github.com/carstenartur/audio-analyzer/actions/workflows/codeql.yml/badge.svg?branch=master)](https://github.com/carstenartur/audio-analyzer/actions/workflows/codeql.yml)
[![License](https://img.shields.io/github/license/carstenartur/audio-analyzer)](LICENSE)
[![SBOM](https://img.shields.io/badge/SBOM-CycloneDX-informational?logo=owasp)](https://github.com/carstenartur/audio-analyzer/dependency-graph/sbom)

**Audio Analyzer** is a Java 21 / Swing **audio and DSP laboratory**. It is useful for
repeatable signal demos, audio-analysis experiments, UI visualization and plugin research. It is
**not** a finished production platform for mosquito tracking.

The root Maven parent is `audioin-parent`; the runnable desktop application is `audio-app`.

## Project status

Stable infrastructure today:

- immutable `AudioBlock` / format models, SPSC ring buffer and deterministic signal generators;
- sample decoding, DSP pipelines, FFT spectrum, RMS/peak measurement, spectrogram and diagnosis
  analyzers;
- Swing dashboard panels for waveform, phase, spectrum, spectrogram, measurements and diagnosis;
- recording/replay, evidence export and Markdown A/B comparison tooling;
- Maven module-boundary tests, unit tests, Spotless, JaCoCo reports/check, Checkstyle/PMD/SpotBugs
  reports and CI baseline checks.

Experimental / research-oriented:

- acoustic-localization plugin code for wingbeat/frequency tracking, TDOA/GCC-PHAT, beamforming,
  Doppler experiments and simulations;
- plugin UI contributions and localization workflows. These are explicitly not validated as a
  production mosquito detector.

## Quickstart

Requires **Java 21** or higher. The Maven Wrapper is included.

```bash
# Build, test, run configured quality checks and produce reports
./mvnw clean verify

# Run the Swing app after package/verify
java -jar audio-app/target/audio-app-0.0.1-SNAPSHOT.jar

# Regenerate README + feature screenshots headlessly
java -cp "audio-app/target/audio-app-0.0.1-SNAPSHOT.jar:audio-app/target/lib/*" \
  org.hammer.tools.DocImageRenderer docs/images

# Optional JMH benchmarks
./mvnw -pl audio-dsp -Pjmh package
```

On Windows use `mvnw.cmd` instead of `./mvnw`; adapt the classpath separator from `:` to `;` for
manual `java -cp` commands.

## Dashboard screenshot

![Audio Analyzer dashboard showing a reproducible 440 Hz sine demo](docs/images/screenshot.png)

_Reproducible documentation screenshot generated headlessly by `DocImageRenderer`: demo mode with a
440 Hz sine signal, waveform, spectrum peak, measurements, spectrogram and diagnosis panels visible
without clipped labels or empty regions._

## Feature groups

### Core audio / DSP platform

- layered capture → ring buffer → DSP → analysis → snapshot flow;
- immutable audio-domain types and UI-independent analysis snapshots;
- deterministic sine/square/chirp/demo generators for tests and demos;
- FFT spectrum, RMS/peak measurement, stereo delay, spectrogram and diagnosis analyzers;
- JMH benchmarks for selected hot paths.

### Swing dashboard

- live/demo input selection, waveform, phase, spectrum, spectrogram, measurements and diagnosis;
- pause/freeze, oscilloscope-style trigger, peak hold and spectrum averaging;
- JavaSound microphone input plus deterministic demo mode.

### Recording / export / comparison

- `.aar` recording and replay through the normal analysis pipeline;
- CSV/PNG and evidence-bundle export;
- Markdown A/B comparison reports for two recordings.

### Plugin / experimental acoustic localization

- `audio-plugin-api` contracts discovered by the app host via Java `ServiceLoader`;
- optional `audio-experimental-acoustic` runtime plugin for research workflows;
- details and limits: [Experimental Acoustic Localization](docs/plugins/acoustic-localization.md).

### Quality / tooling

- Maven multi-module build with Java 21 enforcement;
- Spotless formatting, unit tests and architecture-boundary tests;
- JaCoCo report plus a low 5% bundle line-coverage check;
- Checkstyle, PMD and SpotBugs run during `mvn verify` and fail the build on violations;
- CodeQL workflow with an explicit Maven build.

## Maven modules

```text
audio-core
audio-geometry
audio-acquisition           -> audio-core, audio-geometry
audio-dsp                   -> audio-core
audio-plugin-api            (stable plugin contracts; no audio-* dependencies)
audio-experimental-acoustic -> audio-core, audio-geometry, audio-acquisition,
                               audio-dsp, audio-plugin-api
audio-app                   -> audio-core, audio-dsp, audio-plugin-api
                               runtime: audio-experimental-acoustic plugin
```

- `audio-core` — immutable audio-domain types, snapshots and ring buffer.
- `audio-geometry` — reusable 2D geometry and localization constraints.
- `audio-acquisition` — microphone metadata, arrays, multichannel sources and sample clocks.
- `audio-dsp` — FFT, DSP pipeline, analyzers, diagnostics, spectrogram and stereo-delay logic.
- `audio-plugin-api` — plugin contracts only.
- `audio-experimental-acoustic` — isolated acoustic-localization research plugin.
- `audio-app` — Swing UI, JavaSound/demo wiring, export, plugin host and entry point.

## Documentation

- [Architecture](ARCHITECTURE.md)
- [Quality gates & coverage](docs/quality.md)
- [QA findings & technical debt](docs/QA-FINDINGS.md)
- [Development](docs/development.md)
- [Feature guides](docs/features/README.md)
- [Stereo localization](docs/use-cases/stereo-localization.md)
- [Acoustic localization plugin](docs/plugins/acoustic-localization.md)
- [Roadmap](ROADMAP.md)

## License

This project is licensed under the MIT License. See LICENSE.
