# Demo scenarios

Live demos that can be shown using the `acoustic-localization` plugin loaded into
`audio-app`. All demos use the deterministic scenarios from
`SimulationScenarios` so they look the same on every machine and on CI.

**Status: Implemented**

The **Acoustic Localization Workbench (experimental)** is fully operational and accessible via
**Plugins > Experimental Acoustic Localization > Open: Acoustic Localization Workbench
(experimental)**. It provides:

- Scenario selector dropdown with all nine validation scenarios
- Configurable pipeline parameters (FFT size, frequency band, TDOA estimator, etc.)
- Live per-frame log output during runs
- 2D room map showing microphones (blue), ground truth (green) and tracked positions (red)
- Post-run Markdown, CSV and JSON-lines export

The workbench UI is implemented by `AcousticLocalizationWorkbenchPanel` and covered by
`AcousticLocalizationWorkbenchPanelTest` in `audio-experimental-acoustic`.

For each demo below we list the scenario, what the operator should highlight on screen
and the expected qualitative behaviour. Quantitative pass/fail belongs to
[`experiments.md`](experiments.md).

---

## Demo 1 — Single moving source

### Purpose

Show localization, Doppler velocity estimation and smooth identity-stable
tracking from end to end.

### Setup

- Scenario: `SimulationScenarios.movingSource()`.
- Source moves left-to-right across the room at 4 m/s.
- Select this scenario in the workbench dropdown and click **Run**.

### Visualization

The workbench displays:

- **Log panel** — per-frame tracking output with position, frequency and processing time
- **2D room map** — microphones (blue circles), ground truth trajectory (green triangles) and
  tracked positions (red circles with track IDs)
- **Post-run export** — Markdown summary, CSV per-frame data, JSON-lines for offline analysis

### Expected

- a single persistent `TrackedSource` for the whole run;
- velocity vector roughly aligned with +x and ≈4 m/s magnitude;
- small Doppler shifts that flip sign as the source crosses the array centerline.

---

## Demo 2 — Two sources

### Purpose

Show frequency separation and independent tracking of two simultaneous emitters.

### Setup

- Scenario: `SimulationScenarios.twoMovingSources()` (220 Hz separation, ideal
  for stage demos) or `twoCloseFrequencies()` for the stress variant.
- Select the scenario in the workbench and click **Run**.

### Visualization

The workbench displays:

- **Two distinct track IDs** in the log output, each with its own frequency
- **Two separate trails** on the 2D room map (red circles labeled with track IDs)
- **Frequency clusters** showing two distinct peaks

### Expected

- no identity swaps during the scenario;
- separate trails crossing the room independently in the moving variant.

---

## Demo 3 — Reflection stress test

### Purpose

Show the limits of the current TDOA + beamforming stack under multipath
conditions.

### Setup

- Scenario: `SimulationScenarios.reflectedEnvironment()`.
- Select the scenario in the workbench and click **Run**.

### Visualization

The workbench displays:

- **Log output** showing position estimates with some jitter compared to anechoic scenarios
- **2D room map** with the tracked position occasionally offset from ground truth due to
  multipath interference

### Expected

- visible secondary heatmap peaks near the reflective wall;
- moderate position jitter compared to the anechoic single-source demo, but the
  tracker should still recover the dominant peak each frame.

---

## Demo 4 — Noise stress test

### Purpose

Show graceful degradation as the noise floor approaches the source amplitude.

### Setup

- Scenario: `SimulationScenarios.noisyRoom()` as the starting point.
- For an interactive demo, modify the scenario to sweep noise levels.
- Select the scenario in the workbench and click **Run**.

### Visualization

The workbench displays:

- **Log output** showing degrading confidence as noise increases
- **Frequency peaks** becoming less stable with higher noise
- **Track drops** when the signal-to-noise ratio falls below detection threshold

### Expected

- stable tracking at low noise, increasingly intermittent matching at high
  noise, with the tracker eventually dropping the source instead of producing
  obviously wrong positions.

---

## Demo 5 — Synchronization failure (future)

### Purpose

Show why a shared sample clock matters for TDOA-based localization.

### Setup

Future demo — requires per-channel delay/drift injection that does not yet exist
in `SampleClock`. Once available, run `singleSource()` while injecting a slow
drift of e.g. 1 sample/second on one channel.

### Expected

- localization collapses to a wandering position even though the simulator
  produces a stationary source;
- the demo motivates the synchronization checklist in
  [`../synchronization.md`](../synchronization.md).

