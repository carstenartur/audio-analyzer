# Oscilloscope-style waveform trigger

The waveform panel can lock the visible trace to a repeatable point in a periodic signal. This is the
same basic idea as a benchtop oscilloscope trigger: instead of showing each audio block from an
arbitrary sample boundary, the panel starts the visible window at a detected crossing.

![Triggered waveform display](../images/features/waveform-trigger.png)

## When to use it

Use the trigger when:

- a tone or repeated click appears to slide across the waveform panel;
- you want to compare waveform shape, duty cycle or amplitude from one refresh to the next;
- you are inspecting rare transients and want a stable visual reference.

The trigger is a display feature only. It does not modify audio samples, spectrum analysis,
spectrogram history, recording/replay or evidence export.

## How to use it

In the **File** menu:

1. enable **Waveform: trigger (oscilloscope)**;
2. choose **Waveform: trigger slope** (`Rising ↑` or `Falling ↓`);
3. choose **Waveform: trigger mode**.

Trigger modes:

- **Auto** keeps the display alive. If no trigger fires for a short time, the panel falls back to the
  most recent samples.
- **Normal** shows only real trigger events. Silence or non-periodic content can leave the display
  unchanged until the next crossing is detected.

While enabled, the panel title changes to **Waveform (triggered)** and the overlay reports trigger
state, slope and level.

## Implementation notes

`WaveformTrigger` keeps a rolling history for channel 0 and scans for the configured level crossing.
When enough samples are available after the crossing, it publishes a `TriggeredView` with aligned
samples plus the original frame index and timestamp.

Because the trigger publishes a view rather than editing the audio stream, all other analyzers keep
seeing the unmodified `AudioBlock` sequence.

## Limitations

- Triggering currently uses channel 0.
- The default level is `0.0`, suitable for zero-crossing alignment.
- Very short transients may be hidden by holdoff settings.
- Trigger level and holdoff are available programmatically but are not yet exposed as full UI sliders.
- Directional stereo cues are better inspected with phase or stereo-delay views.

