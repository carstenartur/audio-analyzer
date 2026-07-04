# Spectrum peak hold and exponential averaging

The spectrum panel can display the live FFT together with two optional overlays: exponential averaging
and peak hold. Together they make it easier to distinguish stable tones from short transients.

![Spectrum with averaging and peak hold](../images/features/spectrum-peak-hold.png)

## Concepts

**Exponential averaging** smooths short-term movement in each frequency bin. A steady signal becomes
easier to read because the trace changes less from refresh to refresh.

**Peak hold** remembers the highest magnitude observed in each frequency bin. A short burst remains
visible after the live trace has moved on.

These are display modes. They do not change the raw FFT snapshot used by other analysis components or
recording/export paths.

## When to use averaging

Use averaging when:

- the signal is steady but noisy;
- you need a stable read of dominant frequencies;
- relative peak levels are hard to compare because the live trace jumps too much.

Avoid averaging when the timing of short transients matters. Averaging intentionally smooths fast
changes.

## When to use peak hold

Use peak hold when:

- you are looking for intermittent clicks, alarms or insect-like bursts;
- you want to remember the loudest spectral content encountered during a measurement;
- you are comparing a current live trace with the worst-case trace from the recent session.

Reset peak hold before a new measurement so old session data does not contaminate the current view.

## How to use it

In the **File** menu:

- enable or disable **Spectrum: averaging**;
- enable or disable **Spectrum: peak hold**;
- choose **Spectrum: reset peak hold** to clear remembered peaks without disabling the mode.

## Implementation notes

`SpectrumAverager` maintains an exponential moving average for each frequency bin.
`PeakHoldSpectrum` stores per-bin maxima and can apply a slow decay so old peaks fade over long
sessions. `SpectrumDisplayState` provides the display-layer view consumed by the spectrum panel.

## Limitations

- Peak hold is visual evidence, not a classifier.
- Averaging can hide fast changes.
- Very low-level peaks can still be dominated by windowing, noise or FFT resolution.
- The screenshot is generated documentation evidence; regenerate it when plot labels, colors or layout
  change.

