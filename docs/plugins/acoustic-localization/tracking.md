# Tracking pipeline

This document describes the real-time multi-source tracking pipeline shipped with the
`acoustic-localization` plugin. The pipeline turns the plugin from a collection of
DSP experiments into a coherent acoustic-tracking research platform.

**Status: Implemented and validated**

The pipeline is operational, deterministic and covered by `TrackingPipelineScenarioTest` and
`SourceTrackerTest` in `audio-experimental-acoustic`.

## Stages

```
AudioBlock (one synchronized multichannel frame, frames = blockFrames, channels = N)
  → MultiPeakDetector       (per channel: Hann window → real FFT → multi-peak with parabolic refinement)
  → FrequencyClusterer      (group peaks across channels, identity-aware, Hz+cents tolerance)
  → TdoaEstimator           (GCC-PHAT or normalized cross-correlation, all-pairs)
  → DelayAndSumBeamformer   (best position on a caller-supplied 2D candidate grid)
  → SourceTracker           (identity persistence + 2D constant-velocity Kalman smoothing)
```

The pipeline emits an immutable `TrackingSnapshot` per frame containing the detected
frequency clusters, the active `TrackedSource`s and the wall-clock processing time.

## Source tracking

`SourceTracker` maintains a list of `TrackedSource`s indexed by an integer id assigned the
first time a frequency cluster is observed. For each new frame the tracker:

1. Predicts every active track forward by `dt = currentFrameTimestamp - previousFrameTimestamp`.
2. Matches each new observation to the nearest existing track in frequency space, subject to
   a configurable `frequencyMatchHz` tolerance. Tracks created during the same frame are not
   eligible for re-matching, which prevents identity flips.
3. Updates the matched track's Kalman filter with the localized position; unmatched
   observations become new tracks.
4. Decays the confidence of tracks that were not observed this frame and drops tracks that
   have been missing for `missingFramesToDrop` consecutive frames.

The result is monotonic ids, smoothed positions and a stable view of which sources are
currently active.

## Kalman filter

`Kalman2D` is a small constant-velocity 2D Kalman filter. Its state is `(x, y, vx, vy)` in
meters and meters/second. The process model is piecewise-constant velocity with
configurable acceleration spectral density; the measurement model observes only position.

The implementation uses two scalar updates (x first, then y) so that the entire `predict`
and `update` cycle works with a fixed 4-element state vector and a 4x4 covariance matrix
without allocating per call.

## Real-time readiness

`FrameSchedule(sampleRate, blockFrames, maxLoadFraction)` documents the timing contract the
host must enforce on the pipeline: one block of `blockFrames` samples must be delivered
every `blockFrames / sampleRate` seconds, and the pipeline must complete its processing
within `maxLoadFraction` of that interval. `ProcessingBudget` exposes a lightweight
deadline tracker (`start`, `checkpoint`, `exceeded`) usable from production code and from
deterministic tests via an injectable nano-time supplier.

The pipeline itself does not start threads. It is designed to be driven from a single
dedicated audio thread, while UI consumers read the latest `TrackingSnapshot` via the
plugin's view contributions.

## Configuration knobs

|                         Knob                         |                                                  Purpose                                                  |
|------------------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| `MultiPeakDetector(fft, band, max, snr)`             | FFT length, search band, peaks per channel, minimum SNR over band median                                  |
| `FrequencyClusterer(hz, cents, min, max)`            | Match tolerance (Hz and cents), minimum channels per cluster, output cap                                  |
| `SourceTracker(hz, miss, q, r, p0, v0, decay, gain)` | Match tolerance in Hz, drop horizon, process/measurement noise, initial covariances, confidence smoothing |
| `FrameSchedule(rate, frames, load)`                  | Real-time budget for one block                                                                            |

## Validation scenarios

`org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios` exposes a small
catalogue of deterministic, reproducible experiments:

- `singleSource()` — one stationary tone in an anechoic room;
- `twoCloseFrequencies()` — two stationary sources at distinct positions, ~40 Hz apart;
- `noisyRoom()` — single source with broadband background noise;
- `movingSource()` — one source travelling across the room with constant velocity;
- `movingTowardArray()` — source approaching the array (Doppler validation);
- `movingAcrossArray()` — source moving laterally across the array;
- `twoMovingSources()` — two tones with distinct velocities;
- `reflectedEnvironment()` — single source with strong wall reflections;
- `twoMosquitoWingbeats()` — two deterministic wingbeat emitters at close frequencies.

**All scenarios are executable** via the Acoustic Localization Workbench (experimental) GUI or
programmatically via `WorkbenchScenarioRunner`.

`TrackingPipelineScenarioTest` exercises the full pipeline against each scenario and asserts
the structural guarantees of the platform (deterministic snapshots, identity persistence,
realtime budget, two distinct tracks for two close frequencies, position change for a
moving source).
