# Recording and replay

Audio Analyzer records every published `AudioBlock` into a streamable, integrity-protected `.aarec`
file and can replay the recording through the same analysis pipeline later. Legacy `.aar` recordings
remain readable, but they cannot prove that recording ended cleanly because version 1 had no
completion footer.

## Use cases

Use recording and replay to:

- preserve experiment input with explicit completeness and continuity evidence;
- reproduce a UI or analysis issue that only appears with a specific signal;
- share a minimal recording without sharing the original hardware setup;
- build regression examples from known-good and known-bad sessions;
- run A/B comparisons after a code, hardware or configuration change.

## Recording a session

1. Start a capture source: live input, deterministic demo mode or replay.
2. Choose **File > Start recording...**.
3. Select a destination `.aarec` file.
4. Review the storage preflight result. Recording is refused before the target is created when the
   destination is unwritable or already below the critical safe-capacity threshold.
5. Continue analyzing normally.
6. Watch the persistent **Experiment recording** strip.
7. Choose **File > Stop recording** or use the strip's Stop action.

The recording strip shows:

- lifecycle state and elapsed time;
- destination file name and current bytes written;
- usable filesystem space;
- measured write rate and estimated safe time remaining;
- received and written blocks;
- dropped blocks, source continuity gaps and recorder queue depth;
- complete, incomplete or failed terminal state.

Recording no longer polls the UI's latest-block snapshot. Live, demo and replay sources publish every
block to independent subscribers. `RecordingTap` only enqueues in the producer callback and performs
serialization and disk I/O on its dedicated `ExperimentRecordingWriter` thread. A full queue is never
hidden: dropped blocks and frames are counted and the terminal result becomes `INCOMPLETE`.

The capacity estimate is advisory. Filesystem quotas, network mounts and concurrent writers can
change after a successful check, so write failures are still handled and shown explicitly.

## Replaying a session

1. Inspect the source when its integrity is uncertain by choosing
   **File > Inspect or recover recording...**.
2. Choose **File > Open recording...** for a complete current recording or an accepted legacy
   recording.
3. The active capture service is stopped and replaced by a replay service.
4. Waveform, spectrum, spectrogram, phase, diagnosis and measurement panels process the replayed
   blocks as normal input.

Replay does not loop automatically. When the file is exhausted, replay stops. Reopen the file to run
it again.

## File identity

| Property | Current value |
|---|---|
| Preferred extension | `.aarec` |
| Binary media type | `application/vnd.carstenartur.audio-recording` |
| Current format version | `2` |
| Legacy import extension | `.aar` |
| Legacy format version | `1` |

The new extension avoids collision with Android Archive (`.aar`) files. Extension and media type are
hints only; the reader validates the binary magic value and version.

## Version 2 format and finalization

The format is implemented by `AudioBlockRecordingFormat`, `AudioBlockRecordingWriter` and
`AudioBlockRecordingReader` in `audio-dsp`.

A version 2 file contains:

- a fixed header with magic value, version, channel count, sample rate and source sample format;
- one streamable record per written block;
- normalized 32-bit float samples with frame index and timestamp metadata;
- a completion footer containing block/frame totals, first and last indexes/timestamps, continuity
  gaps, payload size and SHA-256.

A path-backed writer writes to a sibling `.partial` file. Only a successfully flushed footer causes
the partial file to be moved to the requested `.aarec` target; atomic move is used when supported.
Consequently, the presence of the final target means clean container finalization, while source
continuity is reported separately.

## Integrity states

Inspection distinguishes:

- `COMPLETE` — version 2 footer, counters and SHA-256 are valid;
- `LEGACY_UNVERIFIED` — a version 1 file ended cleanly at a block boundary but has no footer;
- `RECOVERABLE_INCOMPLETE` — complete version 2 blocks exist, but no footer was written;
- `TRUNCATED` — input ended inside a block or footer;
- `CORRUPT` — structural fields, footer counters or checksum are invalid.

Strict replay rejects incomplete, truncated and corrupt version 2 files. Inspection can count the
complete blocks without presenting them as successful evidence.

## Non-destructive recovery

**File > Inspect or recover recording...** previews integrity, format version, block/frame counts,
continuity gaps and SHA-256. For recoverable, truncated or corrupt input it can copy every complete
readable block into a separate finalized `.aarec` file. The source bytes are never modified.

Recovery proves the integrity of the new container; it does not recreate samples that were never
written. Provenance should therefore retain the original inspection result and source digest in an
experiment evidence package.

## Programmatic use

Non-UI code can use the same components:

- `AudioCaptureService.subscribe(...)` exposes the complete block stream;
- `RecordingTap` provides bounded background recording and immutable runtime status;
- `RecordingStorageProbe` supports production `FileStore` checks and deterministic test doubles;
- `AudioBlockRecordingWriter` writes current version 2 recordings;
- `AudioBlockRecordingReader.inspect(...)` validates without loading the full recording;
- `AudioBlockRecordingReader.recover(...)` performs non-destructive recovery;
- `RecordedAudioCaptureService` makes a strict recording behave like a capture source.

## Boundaries and limitations

- The recording stores normalized analysis input, not original device packets.
- Hardware profile, calibration, workflow and plugin parameters belong in the portable experiment
  document rather than in the binary recording container.
- Very long recordings must be streamed; `readAll(...)` is intended for bounded replay/comparison
  material.
- A valid footer proves clean container finalization but does not imply that no source blocks were
  dropped before reaching the recorder. The recording runtime status records that separate fact.
- Legacy version 1 files remain importable but can never be upgraded to verified historical evidence
  without an explicit recovery/export step.
