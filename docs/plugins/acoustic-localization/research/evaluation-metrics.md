# Evaluation metrics

Definitions of the metrics used by the experiments in
[`experiments.md`](experiments.md) and demos in
[`demo-scenarios.md`](demo-scenarios.md). All metrics are computed from the
immutable snapshots emitted by the tracking pipeline
(`TrackingSnapshot`, `TrackedSource`) or the localization pipeline
(`AcousticLocalizationSnapshot`) and the ground truth declared by the matching
`SimulationScenarios` factory or by
[`simulation-datasets/`](simulation-datasets/).

## Conventions

- Positions are 2D in meters; velocities in m/s; frequencies in Hz.
- "Per-frame" means once per emitted snapshot (one snapshot per `AudioBlock`).
- Aggregation across a scenario uses the full set of frames after a short warm-up
  window (typically 50 ms) to let frequency tracking and the Kalman filter
  converge.

---

## Localization error

For each frame `t`, a `TrackedSource` is first matched to a ground-truth emitter
`e` (typically by nearest-frequency association). The localization error is then
the Euclidean distance between the matched track's reported position and the
ground-truth position:

```text
err_loc(e, t) = ‖ track.positionMeters() − groundTruth.position(e, t) ‖₂
```

- Unit: meters.
- Aggregations: mean, median, 95th percentile, max.
- Target for the bundled scenarios: less than half the largest inter-microphone
  spacing of the array (≈0.15 m for `defaultArray`).

---

## Velocity error

Difference between the reconstructed and the actual emitter velocity. Per
matched `TrackedSource`, the velocity reported via
`track.velocityMetersPerSecond()` is compared against the ground-truth velocity:

```text
err_vel(e, t) = ‖ track.velocityMetersPerSecond() − groundTruth.velocity(e, t) ‖₂
```

- Unit: m/s.
- The track velocity itself is produced by the 2D Kalman filter and, when
  enabled, fused with the per-microphone radial estimates by
  `VelocityReconstructor`.

---

## Frequency stability

Variance over time of the frequency reported by a matched track:

```text
var_f(e) = Var_t( track.frequencyHz() )
```

- Unit: Hz².
- Low variance indicates stable tracking and a reliable Doppler baseline; large
  spikes typically point at peak-detector confusion under noise or reflections.
- For diagnostics, individual snapshots also expose
  `track.frequencyVarianceHzSquared()` which reflects the internal
  `FrequencyTrack` variance.

---

## Tracking continuity

Two complementary metrics:

- **Identity persistence:** fraction of frames where the `TrackedSource#id`
  attached to ground-truth emitter `e` matches the id observed in the previous
  frame. Target: 1.0.
- **Track switching frequency:** count of id changes per second across the
  scenario duration. Target: 0.

Both are exercised by `SourceTrackerTest` and
`TrackingPipelineScenarioTest` in `audio-experimental-acoustic`.

---

## Doppler consistency

Variance of the per-microphone radial-velocity estimates produced by
`SimpleMultiSensorDopplerEstimator` for the same emitter and frame, computed
over the set of microphones `M` (pseudocode notation: `radialVelocityFor(m, e, t)`
denotes the per-microphone radial-velocity reading that the multi-sensor
estimator consumes for that emitter and frame, not a literal method on the
public API):

```text
var_v_r(e, t) = Var_{m ∈ M}( radialVelocityFor(m, e, t) )
```

- Unit: (m/s)².
- For consumers that only look at the tracked source, the related diagnostic
  `track.radialVelocityStdDevMetersPerSecond()` already aggregates this spread
  per frame.
- Low variance indicates consistent multi-sensor fusion; spikes signal that one
  microphone disagrees, usually because of reflections or peak swaps.

---

## Latency

`TrackingSnapshot` reports the wall-clock processing time of the pipeline for
each frame via `snapshot.processingNanos()`, together with the capture
timestamp of the analysed audio block via `snapshot.sourceTimestampNanos()`.
End-to-end latency at observation time `t_obs` is then:

```text
latency(t) = t_obs − snapshot.sourceTimestampNanos() + snapshot.processingNanos()
```

- Unit: milliseconds (after dividing by 1e6).
- `FrameSchedule` / `ProcessingBudget` expose a per-frame deadline via
  `ProcessingBudget#exceeded()`.
- `WorkbenchRunResult` accumulates per-frame budget compliance:
  - `overBudgetFrameCount()` returns the number of frames where
    `snapshot.processingNanos() > frameSchedule.maxProcessingNanos()`.
  - `isFrameOverBudget(snapshot)` checks a single frame.
- The workbench log annotates over-budget frames with `⚠ OVER BUDGET` and
  includes the aggregate count in the run-complete summary line.
- Markdown, CSV (`budgetExceeded` column) and JSON-lines (`"budgetExceeded"` field)
  exports all include enough data to identify over-budget frames offline.
- Budget warnings are **experimental**: the pipeline never aborts processing;
  warnings reflect wall-clock measurements and may be triggered by JIT warmup,
  GC pauses or OS scheduling jitter on development machines.

---

## False localization rate

Fraction of frames where the matched track's position lies outside an
acceptance disk around the ground-truth position:

```text
flr(e) = | { t : err_loc(e, t) > R } | / N
```

- Default `R = 0.3 m` for the bundled scenarios.
- Used primarily for the reflection and noise-stress experiments.

---

## Signal-to-noise ratio (SNR)

Ratio of the per-frame source energy to the background-noise energy, expressed in
dB. SNR is not yet reported directly by snapshots; it is computed by the
benchmark harness from the configured emitter amplitude and
`Room2D#noiseAmplitude`. SNR is the key control variable for the noise-stress
experiment in [`experiments.md`](experiments.md).

---

## Future-position prediction error

Difference between a predicted future position and the realised ground-truth
position after a prediction horizon `Δ`. For a track observed at frame `t` and a
horizon `Δ` seconds into the future:

```text
err_pred(e, t, Δ) = ‖ predict(track, Δ) − groundTruth.position(e, t + Δ) ‖₂
```

`predict(track, Δ)` may be:

- the constant-velocity extrapolation
  `track.positionMeters() + Δ · track.velocityMetersPerSecond()`; or
- a `Kalman2D.predict(Δ)` step on a copy of the track's internal filter.

Headline horizons used by the prediction experiments in
[`experiments.md`](experiments.md): 10 ms, 50 ms, 100 ms, 250 ms.

- Unit: meters.
- Aggregations: mean and 95th percentile per horizon.
- See [`predictive-tracking.md`](predictive-tracking.md) for the underlying
  assumptions.

---

## Prediction drift over time

Slope of `err_pred(e, t, Δ)` versus horizon `Δ`, evaluated at a fixed frame `t`:

```text
drift(e, t) ≈ d err_pred(e, t, Δ) / d Δ
```

- Unit: meters per second of horizon.
- High drift indicates that the constant-velocity assumption breaks down quickly
  for this scenario, typically because of acceleration or noisy tracking.

---

## Trigger timing error

For event-driven sensing pipelines (see
[`event-driven-sensing.md`](event-driven-sensing.md)), the trigger timing error
is the wall-clock difference between the moment a predicted future event was
scheduled to occur and the moment the realised event actually occurred:

```text
err_trigger = t_actual − t_predicted
```

- Unit: milliseconds.
- Aggregations: mean, standard deviation, 95th percentile.
- The metric is only meaningful when a ground-truth event time is available
  (e.g. from the simulator or from a calibrated reference sensor).

---

## Confidence decay vs prediction horizon

How the tracking-confidence proxy degrades as a function of prediction horizon.
Two complementary observations:

- the per-track `track.confidence()` value reported at frame `t`;
- the position variance reported by the underlying filter, available via
  `Kalman2D.positionVariance()` after a `predict(Δ)` step on a copy of the
  track's filter state.

A useful summary statistic is the ratio:

```text
decay(Δ) = positionVariance_after_predict(Δ) / positionVariance_at_t
```

- Decay close to 1 indicates that the prediction stays inside the current
  uncertainty budget; large values indicate that the prediction is dominated by
  process-noise growth.
- See [`predictive-tracking.md`](predictive-tracking.md) for how this is used to
  pick a usable horizon.

