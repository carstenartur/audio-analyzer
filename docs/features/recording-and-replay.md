# Recording and replay

Audio Analyzer can capture produced `AudioBlock`s into a small `.aar` recording and replay that file
through the same analysis pipeline later. This makes short-lived issues reproducible and gives QA or
research work a durable evidence format.

## Use cases

Use recording and replay to:

- reproduce a UI or analysis issue that only appears with a specific signal;
- share a minimal example without sharing the original hardware setup;
- build regression examples from known-good and known-bad sessions;
- run A/B comparisons after a code, hardware or configuration change.

## Recording a session

1. Start a capture source, either live input or deterministic demo mode.
2. Choose **File > Start recording...**.
3. Select a destination `.aar` file.
4. Continue analyzing normally.
5. Choose **File > Stop recording** when done.

Recording is best effort. Blocks are deduplicated by frame index, but extreme capture rates or a
paused UI may still miss some blocks. For typical bundled live capture settings, the default polling
interval is intended to be sufficient.

## Replaying a session

1. Choose **File > Open recording...**.
2. Select a `.aar` file.
3. The active capture service is stopped and replaced by a replay service.
4. Waveform, spectrum, spectrogram, phase, diagnosis and measurement panels process the replayed
   blocks as normal input.

Replay does not loop automatically. When the file is exhausted, replay stops. Reopen the file to run
it again.

## File format

![Recording file layout](../images/features/recording-format.png)

The format is implemented by `AudioBlockRecordingFormat`, `AudioBlockRecordingWriter` and
`AudioBlockRecordingReader` in `audio-dsp`.

The file contains:

- a fixed header with magic value, version, channel count, sample rate and sample format;
- one frame record per written block;
- normalized 32-bit float samples stored with frame index and timestamp metadata.

The format is intentionally simple. It is not a replacement for WAV or AIFF; it exists to preserve the
analysis pipeline's block-level view of audio for deterministic replay.

## Programmatic use

Non-UI code can use the same components:

- `AudioBlockRecordingWriter` writes blocks to an `OutputStream`;
- `AudioBlockRecordingReader` loads or streams recording blocks;
- `RecordedAudioCaptureService` makes a recording behave like a capture source;
- `RecordingTap` connects the Swing app's current capture source to the recording writer.

## Limitations

- The file stores normalized samples produced by the capture service, not original device metadata.
- Very long recordings can exceed memory if fully loaded; stream with the reader for larger files.
- The current format version is strict: unsupported versions are rejected.
- Recording/replay preserves analysis input, not complete application UI state.
