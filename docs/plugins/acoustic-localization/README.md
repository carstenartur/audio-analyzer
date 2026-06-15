# Acoustic localization plugin details

This detail page expands the [plugin entry page](../acoustic-localization.md). The package is an
experimental research subsystem for real-time localization and tracking of weak or insect-like sound
sources. It is not a production mosquito detector.

## How to run the workbench

The Plugins menu in Audio Analyzer exposes an interactive **Acoustic Localization Workbench
(experimental)** that lets you run deterministic simulation scenarios through the full tracking
pipeline without a microphone array.

### Opening the workbench

1. Start Audio Analyzer.
2. Open the **Plugins** menu in the menu bar.
3. Expand the **Experimental Acoustic Localization** submenu.
4. Click **Open: Acoustic Localization Workbench (experimental)**.

A dialog opens with three areas:

- **Top** — scenario selector, pipeline parameter controls and Run / Stop buttons.
- **Centre left** — log, Markdown, CSV and JSON-lines tabs showing live and post-run output.
- **Centre right** — 2-D room map: room rectangle, microphone positions (blue circles), emitter
  ground-truth positions (green triangles), candidate grid (grey dots) and estimated track
  positions (red circles, labeled by track ID).

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

Processing time exceeding the per-block budget (block duration × 0.8) is reported in the final
summary.

### Export

After a run completes the three export tabs are populated:

- **Markdown** — human-readable summary with scenario metadata, parameter table and per-block table.
- **CSV** — one row per tracked source per frame (`frameIndex`, `timestampNs`, `trackId`,
  `frequencyHz`, `posX`, `posY`, …).
- **JSON-lines** — one JSON object per frame, suitable for offline analysis.

Copy the content to a file for archiving.

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

---

## Pipeline overview

```text
MultiChannelAudioSource
  -> AudioBlock
  -> WingbeatFrequencyTracker
  -> TDOA estimator (cross-correlation or GCC-PHAT)
  -> DelayAndSumBeamformer
  -> AcousticLocalizationSnapshot
  -> AcousticDebugFrame / renderer
```

Implemented today:

- stable acquisition metadata (`Microphone`, `MicrophoneArray`, `MultiChannelAudioSource`,
  `SampleClock`);
- experimental frequency tracking over a configurable band;
- experimental cross-correlation and frequency-domain GCC-PHAT TDOA estimators;
- configurable `MosquitoLocalizationPipeline` pairing all microphone pairs by default, with an
  explicit reference-channel mode for calibrated experiments;
- delay-and-sum beamforming over a caller-supplied candidate grid;
- deterministic simulation with moving emitters, multiple emitters, reflections and noise;
- architecture boundary tests that prevent stable packages from importing experimental code.

Still experimental:

- insect/species identification, robust multi-insect tracking and real-world calibration workflows;
- sub-sample delay estimation, probabilistic data association and room impulse-response modelling;
- additional plugin-contributed heatmap/confidence visualizations.

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
