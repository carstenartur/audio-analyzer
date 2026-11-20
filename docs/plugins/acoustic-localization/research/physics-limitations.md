# Physics and engineering limitations

> Limitations that bound what the `audio-experimental-acoustic` subproject
> can reasonably claim. The list complements the implementation-level
> [plugin interpretation rules](../README.md#interpretation-rules) by
> grounding them in the physics and the sampling/signal-processing constraints
> that apply regardless of the implementation language.

## Prediction instability for non-constant motion

The bundled tracker assumes a 2D constant-velocity process model
(`Kalman2D`). Any acceleration, sudden direction change or chaotic trajectory
component is absorbed by the process noise rather than modelled explicitly.
Consequently:

- prediction error may grow super-linearly with horizon when the source
  accelerates;
- a single direction change inside a prediction window can move the predicted
  position arbitrarily far from the realised position;
- evaluating predictions on accelerating sources (planned in
  [`experiments.md`](experiments.md)) is expected to expose this regime.

## Uncertainty growth over time

Even for a purely constant-velocity source, the position variance reported by
`Kalman2D.positionVariance()` grows after each `predict(Δ)` step due to the
configured process noise. The metric *Confidence decay vs prediction horizon*
in [`evaluation-metrics.md`](evaluation-metrics.md) tracks this growth. There
is no finite horizon at which a Kalman prediction stays as certain as a
direct measurement; in practice consumers should pick a horizon at which the
inflated variance is still acceptable.

## Latency constraints

Every observation reported by `TrackingPipeline` is, by construction, in the
past. The combination of capture buffering, FFT framing, TDOA estimation,
beamforming and Kalman update produces a per-frame processing delay surfaced
by `TrackingSnapshot.processingNanos()`. Predictive tracking can compensate
for this delay, but only up to the horizon at which the prediction itself
remains useful (see RQ2 in [`research-questions.md`](research-questions.md)).

## Sampling-rate limitations

Spectral analysis inside the pipeline is bounded by the FFT bin spacing
`Δf = sampleRate / N`. For the bundled 16 kHz scenarios and the FFT sizes used
in `MultiPeakDetector`, two narrowband sources whose fundamentals are closer
than a few bins cannot be reliably separated without sub-bin interpolation
(see Experiment F in [`experiments.md`](experiments.md)).

Doppler accuracy inherits the same limit: a velocity that shifts the observed
frequency by less than one bin is, in the absence of parabolic refinement,
indistinguishable from zero motion. The Doppler-resolution discussion in
[Physics and latency limits](../physics-and-latency-limits.md#snr-frequency-resolution-and-doppler-velocity-limits)
expands on this.

## Synchronization precision requirements

TDOA-based localization needs synchronized samples or a measured timing model across the
microphones. The sample period at 16 kHz is ≈62.5 µs, which corresponds to
≈21 mm of equivalent path difference in air. For the 30 cm square `defaultArray()`, a single-sample
drift represents about 7% of the delay across one side and 5% of the largest geometric delay across
the 0.424 m diagonal; multi-sample drift quickly consumes this timing budget.
This is the regime that Experiment I and RQ6 are designed to probe.

The calibration layer implements static-offset and affine-drift corrections with explicit
uncertainty bounds. It does not automatically combine independent USB devices, reconstruct their
sample streams or continually re-estimate timing; see
[unsupported synchronization work](../synchronization.md#what-remains-experimental-or-unsupported).

## Spatial resolution limits

For a given array geometry, the localization resolution is bounded by:

- the maximum inter-microphone delay: for the 30 cm square `defaultArray()`, the diagonal is about
  0.424 m, corresponding to about 20 samples at 16 kHz and 343 m/s;
- integer-sample quantization in the default GCC-PHAT estimator, or the interpolation uncertainty
  of the selectable sub-sample estimator;
- the beamforming grid resolution supplied by the caller to
  `DelayAndSumBeamformer`.

Predictions that pretend to be sharper than the underlying localization are
unphysical; reported uncertainty should therefore never be smaller than these
geometric bounds.

## Reflections and multipath

Reflected energy can produce false beamforming peaks and false TDOA matches.
The current simulator only models a simple specular reflection regime
(`Room2D.reflectionGain`); real rooms exhibit richer multipath that may break
the assumptions used by the tracker. Experiment H is the place where this
ceiling is exercised today.

## Air absorption and microphone response

Air absorption and per-microphone frequency response are not modelled in the
simulator. For real rigs these effects appear as a frequency-dependent SNR
reduction and as per-channel gain/phase mismatch, both of which feed directly
into beamforming sidelobes and into Doppler-velocity bias.

## Closing note

None of the limitations above is a defect: they are the boundaries inside
which the documented metrics and predictions remain meaningful. The role of
this document is to make those boundaries explicit so the rest of the
research notes can stay short and grounded.
