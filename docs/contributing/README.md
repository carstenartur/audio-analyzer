# Contributing

This guide complements [`docs/development.md`](../development.md) (build, code style, CI) with
**a map of where things belong** and **how to extend the platform safely**. It is the entry
point for new contributors.

For the high-level project structure and dependency rules see
[`ARCHITECTURE.md`](../../ARCHITECTURE.md). For known gaps and follow-ups see
[`docs/QA-FINDINGS.md`](../QA-FINDINGS.md).

## Repository map

|            Module             |    Stability     |                                                    Contains                                                     |
|-------------------------------|------------------|-----------------------------------------------------------------------------------------------------------------|
| `audio-core`                  | **Stable**       | `AudioBlock`, `AudioFormatDescriptor`, `AudioRingBuffer`, immutable `WaveformSnapshot`/`PhaseScopeSnapshot`.    |
| `audio-geometry`              | **Stable**       | 2D positions, rays and localization geometry primitives.                                                        |
| `audio-acquisition`           | **Stable**       | `Microphone`, `MicrophoneArray`, `MultiChannelAudioSource`, `SampleClock` (no JavaSound / no Swing).            |
| `audio-dsp`                   | **Stable**       | DSP pipeline, FFT, analyzers (`RmsPeak`, `Spectrum`, `StereoDelay`, `Spectrogram`, `Diagnosis`), generators.    |
| `audio-plugin-api`            | **Stable**       | Host-facing plugin contributions, including versioned experiment sections.                                      |
| `audio-experiment-document`   | **Stable**       | Portable experiment codec, schema validation, migrations, session lifecycle and CLI.                            |
| `audio-experimental-acoustic` | **Experimental** | Wingbeat tracking, GCC-PHAT/cross-correlation TDOA, delay-and-sum beamformer, 2D room simulator. Research code. |
| `audio-web-editor`            | Application      | React Flow UI source and reproducible production assets.                                                        |
| `audio-app`                   | Application      | Swing UI, JavaSound capture wiring, demo capture service, CSV / PNG / evidence-bundle export, entry point.      |

Stable modules must not depend on `audio-app` or `audio-experimental-acoustic`. The
`ArchitectureBoundaryTest` in `audio-app` enforces this for both source imports and POM
dependencies.

## Where new code belongs

|                              Adding ...                              |                                          Put it in ...                                           |
|----------------------------------------------------------------------|--------------------------------------------------------------------------------------------------|
| A new immutable audio-domain type or snapshot reused across modules  | `audio-core`                                                                                     |
| A new reusable multi-channel acquisition contract                    | `audio-acquisition`                                                                              |
| A new DSP processor or analyzer that is generally useful             | `audio-dsp` (under `dsp`, `analysis`, `localization`, `diagnosis`, `spectrogram` as appropriate) |
| A research-only algorithm (mosquito tracking, TDOA experiments, ...) | `audio-experimental-acoustic`                                                                    |
| A Swing panel, renderer, theme or export format                      | `audio-app` (`org.hammer.*`, `org.hammer.audio.ui`, `org.hammer.audio.export`)                   |
| JavaSound / device wiring                                            | `audio-app` (`org.hammer.audio.AudioCaptureServiceImpl`, `AudioLineProvider`)                    |

If you find yourself wanting to import `org.hammer.audio.experimental.*` from a stable module,
stop: that import is what `ArchitectureBoundaryTest` exists to prevent. Promote a minimal,
generic interface into `audio-core` / `audio-dsp` first and have the experimental code depend on
that.

## How to add a new analyzer

1. Define an immutable `XxxSnapshot` implementing `AnalysisSnapshot` (carries the analyzed
   values plus source `frameIndex` / `timestampNanos`).
2. Implement `AnalysisModule<XxxSnapshot>` returning the snapshot.
3. Place both in the package that best matches the responsibility (`analysis`, `localization`,
   `diagnosis`, `spectrogram`, ...).
4. Add a unit test driven by `SignalGenerator` / `DemoPresetGenerator` rather than live audio.
5. Wire it into the UI in `audio-app` only when it is ready to be exposed.

## How to add a new DSP experiment safely

Experimental algorithms (especially in acoustic localization) should:

- Live entirely under `org.hammer.audio.experimental.acoustic` (or a sibling experimental
  package), never in stable modules.
- Depend only on `audio-core`, `audio-geometry`, `audio-acquisition` and `audio-dsp`.
- Carry honest limitations in Javadoc and any matching docs page (no production-sounding
  language; see [`docs/plugins/acoustic-localization/README.md`](../plugins/acoustic-localization/README.md)
  for tone reference).
- Be validated with deterministic generators or the 2D simulator before requiring real
  recordings.
- Reuse the stable `AudioBlock` / `AnalysisSnapshot` types instead of inventing parallel
  domain models.

Algorithms that prove themselves stable and broadly useful can later be promoted into
`audio-dsp` behind a generic interface.

## Tests

- Unit tests live next to the production code they exercise (`<module>/src/test/java/...`).
- Architecture-boundary checks live in `audio-app/src/test/java/org/hammer/audio/ArchitectureBoundaryTest.java`.
  Add new boundary assertions there when introducing new stable modules or packages.
- Prefer `SignalGenerator` / `DemoPresetGenerator` / `SimulatedMicrophoneArraySource` over
  live audio for deterministic, headless-friendly tests.
- Tests run with `java.awt.headless=true` — see [`docs/development.md`](../development.md)
  for Swing-in-headless tips.

## Coding expectations

- Follow the existing google-java-format / Spotless formatting; run `./mvnw spotless:apply`
  before committing.
- Keep public API additions documented with Javadoc; mark experimental APIs as such.
- Do not silently change the signature of types under `audio-core`, `audio-geometry`,
  `audio-acquisition` or `audio-dsp`: these are the modules other code is built against.
- Update the relevant `docs/` page when behaviour changes; stale docs are the main source of
  drift this repository has had to clean up.

## Submitting changes

1. `./mvnw spotless:apply` (formatting).
2. `./mvnw clean verify` (build, tests, Checkstyle / SpotBugs / PMD / Spotless / JaCoCo report).
3. Describe behaviour changes and any doc updates in the PR.
4. CI runs the same gates and uploads reports as artifacts.

