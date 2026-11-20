# Microphone array setup

Practical notes for building a real microphone array that mirrors the simulator
defaults used by [`SimulationScenarios.defaultArray()`](../../../../../audio-experimental-acoustic/src/main/java/org/hammer/audio/experimental/acoustic/simulation/SimulationScenarios.java).
These notes complement the [synchronization checklist](../../synchronization.md) and the maintained
[microphone operator workflow](../../../../features/microphone-experiments.md).

## Reference geometry

The bundled simulator uses a planar 4-microphone array on a rigid frame:

| Mic | Position (m) | Channel |
|-----|--------------|---------|
| m0  | (1.35, 0.0)  | 0       |
| m1  | (1.65, 0.0)  | 1       |
| m2  | (1.35, 0.3)  | 2       |
| m3  | (1.65, 0.3)  | 3       |

- Inter-microphone spacing: 0.30 m in both axes.
- Array footprint: 30 × 30 cm square.
- Sample rate: 16 kHz.
- One sample at 16 kHz corresponds to ≈21.4 mm of path difference in air at
  c ≈ 343 m/s.

When you build the physical version, the coordinate frame must match the values
above for comparison with the bundled simulator geometry. The standalone provider searches
`x = 0…3 m`, `y = 0…2 m`; moving the microphones does not resize that search region. A matching
physical layout does not create ground truth automatically: measure the real source positions
independently and retain those measurements with the recording.

## Hardware checklist

- **Synchronous capture device.** Use one multichannel audio interface with a
  single shared clock for TDOA. The current live workflow opens one JavaSound multichannel device;
  the calibration framework separately models measured channel offsets and affine drift. It does
  not combine several independent capture devices. An **experimental external proposal** for
  low-cost setups uses three stereo USB microphones (treated as locally
  synchronized pairs) with an ultrasonic reference beacon to estimate
  inter-device delay offset, clock drift and cycle slips; see
  [`../../physics-and-latency-limits.md`](../../physics-and-latency-limits.md#independent-usb-microphones-and-ultrasonic-reference-beacon-calibration).
  A 35–45 kHz beacon requires a capture chain that passes that band and a host
  sample rate of 96 kHz or higher (Nyquist), and beacon-based correction is
  **not implemented** in the current codebase.
- **Rigid frame.** Mount all microphones on a rigid support and measure their positions in the chosen
  coordinate frame. Record position uncertainty alongside timing uncertainty; numerical coordinates
  alone do not establish physical localization accuracy.
- **Identical microphones.** Use the same model, batch and orientation; gain or
  phase mismatch directly maps to beamforming sidelobes.
- **Cable routing.** Match cable lengths and avoid mains/ground loops; hum at
  50/60 Hz and harmonics is loud relative to weak targets.
- **Stand and isolation.** Decouple the frame from the table or floor; vibration
  feeds into every channel coherently and corrupts beamforming.

## Calibration procedure

1. Measure microphone coordinates in metres, label the channel wiring and choose the reference
   channel. The browser's simple calibration form requires channel 0 as reference at frame 0.
2. Capture repeatable common reference events on every channel using the capture device's recording
   tools or an acquisition integration. The standalone live-localization route requires calibration
   before it starts; it is not a calibration-recording wizard. Retain the raw calibration audio,
   sample rate, event frame indices, device settings and measurement times.
3. Isolate the hardware timing offset from acoustic travel time. For an acoustic click/chirp, retain
   the measured loudspeaker position and account for unequal propagation distances. The correlation
   estimator does not infer those distances or automatically distinguish them from device delay.
4. The programmatic `ArrayTimingCalibrationEstimator.observe(...)` estimates relative delay for one
   common event. `calibrate(...)` uses two events with a known positive frame separation to fit an
   offset and affine drift. The [synchronization guide](../../synchronization.md#deterministic-calibration-workflow)
   describes these APIs and their deterministic test fixtures. A synthetic fixture demonstrates the
   method; it is not measured calibration for a physical array.
5. Evaluate residual/jitter, gain and polarity from the actual measurements. The estimator's
   correlation-derived residual estimate, zero jitter and unit-gain defaults do not establish those
   physical measurements. Keep the validity interval and uncertainty evidence with the result.
6. Enter the measured values in **Edit geometry, device format and calibration** after all geometry,
   device and format edits. If the timing fit uses another reference frame/channel, retain it in the
   advanced portable definition instead of pasting its values unchanged into the frame-0 form.
   Choose **Apply measured calibration to draft**, then **Validate & save profile** and perform a new
   readiness check. The desktop exposes the measurements in **Edit profile settings…**.
7. Run a recorded localization check against independent measured loudspeaker positions within the
   search region. Compare reported tracks and synchronization diagnostics with those positions;
   there is no guaranteed tolerance of one array spacing. Preserve the profile, raw input, reports
   and reference measurements as described in the [evidence workflow](../../../../features/microphone-experiments.md#export-and-reopen-evidence).

## Synchronization budget

A one-sample error at 16 kHz is ≈21 mm of equivalent path difference and at
48 kHz still ≈7 mm. For a 30 cm square array, the 16 kHz error is roughly 7% of the delay across one
side and 5% of the maximum geometric delay across the 0.424 m diagonal. Real microphone rigs should:

- capture calibration impulses before and after each session;
- bound clock drift to less than one sample over the longest recording;
- reject data when drift exceeds the localization error budget.

`SampleClock` stores nominal timestamps. The implemented calibration layer can correct measured
static offsets and affine drift in the TDOA estimates, with explicit residual/jitter bounds. It does
not automatically remove USB buffering jitter, detect cycle slips, resample independent streams or
continuously re-estimate drift. See [unsupported synchronization work](../../synchronization.md#what-remains-experimental-or-unsupported).

## Mapping real recordings to simulator scenarios

Each scenario in [`../simulation-datasets/`](../simulation-datasets/) carries
explicit ground-truth positions, velocities and frequencies. A real-world rig
that reproduces the simulator geometry can support a future comparison experiment:

1. Replaying the same emitter geometry with a loudspeaker on a motion stage.
2. Recording with the synchronized array and preserving the microphone workflow's `.aarec`, complete
   profile and evidence ZIP, plus an independent reference trajectory.
3. Implementing the explicit alignment/comparison of recorded estimates with that reference and
   computing the metrics in [`../evaluation-metrics.md`](../evaluation-metrics.md).

The current microphone workflow does not import WAV plus a reference trajectory as an automatic
real-world benchmark. Its live/replay output explicitly reports benchmark ground truth as unavailable.
The comparison above remains Experiment J in [`../experiments.md`](../experiments.md), separate from
the implemented capture and evidence-export path.
