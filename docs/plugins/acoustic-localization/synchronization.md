# Synchronization requirements

TDOA-based localization is fundamentally an inter-channel timing measurement. The accuracy of every downstream stage in the [tracking pipeline](tracking.md) is bounded by the sample-accurate alignment of the channels feeding it. Synchronization quality is therefore represented as data and travels with localization/tracking snapshots; it is not an undocumented assumption of the estimator.

## Preferred production-like path: one shared sample clock

The most reliable path remains microphones sharing one hardware sample clock. Frame `n` on channel A and frame `n` on channel B are then captured by one clock domain, and callers use the explicit `NOMINAL_SHARED_CLOCK` synchronization mode.

Sources that satisfy this requirement include:

- a single multichannel audio interface where every input is sampled from the same crystal;
- multiple interfaces locked through word clock or ADAT and exposed as one host stream;
- a dedicated multichannel field recorder used in line-in mode.

The host audio framework must deliver one multichannel stream so that the platform receives one `AudioBlock` with `channels = N`. `SampleClock` remains the nominal frame-to-timestamp mapping for that stream; it is intentionally not expanded into an inter-device correction engine.

## Implemented experimental path: calibrated independent clock domains

Independent devices can now be represented by `MicrophoneArrayCalibration`. The profile covers every channel and records:

- a reference channel;
- static channel delay in samples at a known reference frame;
- affine sample-rate drift in PPM;
- calibration-fit residual and short-term jitter in samples;
- gain correction and polarity metadata;
- calibration and expiration timestamps.

`ChannelTimingCalibration.offsetAtFrame(frameIndex)` predicts the hardware delay at the absolute nominal frame. `CalibratedTdoaEstimator` subtracts the second-minus-first hardware delay from a delegate estimator before generating localization constraints.

Every calibrated observation is assessed against a caller-defined timing-error budget:

- `TRUSTED` — current profile, comfortably inside the budget;
- `DEGRADED` — usable, but more than half of the budget is consumed;
- `REJECTED` — expired profile or estimated residual/jitter exceeds the budget.

`REJECTED` observations are stopped before localization/tracking returns evidence. The assessment is included in `AcousticLocalizationSnapshot` and `TrackingSnapshot`, allowing the workbench and exporters to present the exact mode, status, error estimate and diagnostics.

## Deterministic calibration workflow

The implemented calibration foundation is deliberately hardware-neutral and CI-safe:

1. Record or generate a common calibration pulse on all channels.
2. Use `ArrayTimingCalibrationEstimator.observe(...)` to estimate each channel delay relative to a selected reference by normalized cross-correlation.
3. Repeat the calibration event after a known number of nominal frames.
4. Use `calibrate(...)` to derive the static offset and affine drift in PPM.
5. Store the resulting immutable profile with a validity window.
6. Wrap the chosen TDOA implementation with `CalibratedTdoaEstimator` and configure the maximum acceptable timing error in samples.
7. Inspect the synchronization assessment in every localization/tracking result.

`SyntheticCalibrationFixture` provides deterministic common-pulse blocks for tests and simulation. CI proves recovery of known offsets and known drift without requiring live hardware.

## What remains experimental or unsupported

The framework does **not** claim that arbitrary USB microphones become trustworthy merely because a profile exists. A real setup must still provide repeatable calibration recordings and measured residuals.

The current implementation does not yet provide:

- automatic ultrasonic-beacon detection in a live capture service;
- cycle-slip detection or sample insertion/removal;
- continuous online re-estimation while localization is running;
- resampling into a reconstructed virtual common clock;
- automatic geometry measurement;
- a validated hardware-specific accuracy claim.

A setup with independent USB microphones is therefore experimental. If drift is non-affine, calibration expires, or cycle slips occur, the profile must be rejected and renewed rather than silently reused.

A practical low-cost research setup may use several stereo USB devices: channels inside each device share a local clock, while repeated common calibration events estimate the offset and drift between device pairs. The profile and error budget must be derived from measurements for that exact setup.

See [Physics and latency limits](physics-and-latency-limits.md) for the underlying path-difference, reference-beacon, drift, ambiguity and latency bounds.

## Timing precision requirements

For a planar array of side `d`, a wavefront from a distant source crosses the array in at most `d / c` seconds (`c` ≈ 343 m/s). The current TDOA outputs use integer-sample delays; calibrated affine correction is rounded at the estimator boundary while residual/sub-sample uncertainty remains visible in the synchronization assessment.

Useful resolution therefore requires the smaller of:

- `1 / sampleRate` ≤ desired-angular-resolution × `d / c`;
- combined calibration residual and inter-channel jitter below the configured error budget.

At 48 kHz the sample period is about 20.8 µs. Representative ideal upper bounds are:

| Array spacing | Time of flight | Approximate broadside step at 48 kHz |
|---------------|----------------|--------------------------------------|
| 5 cm          | 146 µs         | ~7°                                  |
| 15 cm         | 437 µs         | ~2.5°                                |
| 30 cm         | 875 µs         | ~1.3°                                |

Reverberation, geometry uncertainty, noise, gain/polarity mismatch and synchronization residuals reduce achievable accuracy further.

## Capture-side checklist

Before treating localization as more than demonstration-grade, verify:

1. The channel mapping and microphone IDs match the calibration profile exactly.
2. Microphones are mechanically rigid and their positions are measured to within the required geometry budget.
3. Gain and polarity are known and stable.
4. For shared-clock capture, the host exposes one real multichannel clock domain.
5. For calibrated independent clocks, two or more repeatable calibration events recover offsets and drift within configured bounds.
6. The profile is current and every returned snapshot reports `TRUSTED` or an explicitly accepted `DEGRADED` status.
7. The simulator remains the ground-truth upper bound for the selected algorithm and array geometry.

If these conditions are not met, reject the localization result or label it demonstration-grade; do not hide synchronization uncertainty behind a position marker.
