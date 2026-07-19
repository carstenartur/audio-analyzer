# Stereo delay and broad direction estimation

Audio Analyzer can estimate the time delay between the left and right channels of a stereo microphone pair. With known microphone spacing, that delay can be converted into a path-length difference and a broad angle-of-arrival estimate.

This is useful for learning, diagnostics and controlled experiments. It is not full source localization.

## What the measurement answers

Given sufficiently correlated left and right signals, `StereoDelayAnalyzer` can:

- report whether the right channel is delayed or advanced relative to the left;
- report delay in samples and milliseconds;
- convert delay to path-length difference with the configured speed of sound;
- estimate an approximate arrival angle when microphone spacing is known;
- report confidence/status needed to judge the result;
- reject silence, mono input, weak correlation and physically impossible delays.

Typical questions include:

- Is a transient consistently reaching the left microphone first?
- Did a channel swap invert the expected direction?
- Does a controlled source movement produce a repeatable delay trend?
- Is the stereo pair sufficiently correlated for a delay estimate at all?

## A controlled experiment

Use synchronized two-channel input or a deterministic fixture:

1. Place two microphones at a measured separation.
2. Keep gain and signal paths as similar as possible.
3. Use a short broadband sound or another signal with a clear correlation peak.
4. Record geometry, sample rate and channel order.
5. Repeat the measurement with the source moved to the other side.
6. Compare delay sign, magnitude and confidence rather than relying only on a direction label.

A deterministic synthetic delay fixture is preferable when validating code. Real microphones add room, hardware and synchronization effects.

## How it works

The analyzer computes normalized cross-correlation between channels across candidate lags. The strongest acceptable peak determines the estimated delay:

- positive delay: the right channel lags the left;
- negative delay: the right channel leads the left.

The estimate is converted with:

```text
delayMs = 1000 * delaySamples / sampleRate
pathDifferenceMeters = speedOfSound * delaySamples / sampleRate
angleDegrees = asin(pathDifference / microphoneSpacing)
```

The default microphone spacing is `0.20 m`; the default speed of sound is `343 m/s`. Use the real measured spacing and an appropriate environmental value when interpretation matters.

## Physical interpretation

A measured time difference constrains the difference in path length to the two microphones. It does not uniquely determine a point in two- or three-dimensional space.

Many source positions share the same path-length difference. Additional microphones, known geometry and a localization method are required to estimate a point or trajectory.

## Demo scenarios

In **Demo mode**, useful deterministic presets include:

- **Stereo delay test** — fixed inter-channel delay;
- **Mosquito-like high-frequency burst** — intermittent synthetic bursts with noise and a small echo component;
- **Moving chirp source** — changing inter-channel delay;
- **50 Hz hum + harmonics** — stable mains-hum-style bands;
- **Clipping test** — intentionally clipped waveform.

The mosquito-like demo is a signal fixture, not a species detector.

## Common failure modes

- **Reflections** create competing correlation peaks.
- **Weak or narrowband signals** make the peak ambiguous.
- **Independent channel clocks** add offset and drift that can dominate acoustic delay.
- **Gain or frequency-response mismatch** reduces correlation.
- **Clipping** changes the waveform and may bias the estimate.
- **Incorrect channel order** reverses the reported direction.
- **Unknown microphone spacing** prevents meaningful geometric interpretation.
- **Very small delays at low sample rates** require sub-sample estimation and explicit uncertainty.

## What not to claim

A stereo delay estimate alone does not prove:

- exact distance;
- exact azimuth and elevation;
- full 2D or 3D position;
- species identity;
- reliable tracking in reverberant environments.

Treat stable readings as evidence of broad left/right direction, not exact source coordinates.

## Reproducibility record

For a result that may be compared later, record:

- Audio Analyzer version or commit;
- input source and recording identity;
- sample rate and channel format;
- microphone model, spacing and orientation;
- source position and signal type;
- room/environment description;
- analyzer settings;
- estimated delay, angle, confidence and rejected frames.

Store or link the `.aar` recording so the same audio can be replayed through the same analysis path.

## Related documentation

- [Getting started](../getting-started.md)
- [Recording and replay](../features/recording-and-replay.md)
- [Experimental acoustic localization](../plugins/acoustic-localization.md)
- [Synchronization and calibration](../plugins/acoustic-localization/synchronization.md)
- [Physics and latency limits](../plugins/acoustic-localization/physics-and-latency-limits.md)

