# Recording and replay

Audio Analyzer records every published `AudioBlock` into a streamable, integrity-protected `.aarec`
file and can replay the recording through the same analysis pipeline later. Legacy `.aar` recordings
remain readable, but they cannot prove that recording ended cleanly because version 1 had no
completion footer.

The steps below use the desktop experiment host launched by the
[current desktop startup command](../getting-started.md#explore-a-signal-in-the-desktop-workbench).
For the browser's microphone-array experiment, use the separate
[simulation, live, replay and evidence workflow](microphone-experiments.md). Its replay preflight is
stricter than the generic desktop signal viewer.

## Use cases

Use recording and replay to:

- preserve experiment input with explicit completeness and continuity evidence;
- reproduce a UI or analysis issue that only appears with a specific signal;
- share a minimal recording without sharing the original hardware setup;
- build regression examples from known-good and known-bad sessions;
- run A/B comparisons after a code, hardware or configuration change.

## Recording a session

1. Select a capture source and start it. For a first hardware-free round trip, choose **Demo mode**,
   select a sine signal and enable **Start/Stop**. Confirm that the waveform changes before recording.
2. Choose **File > Start recording...**.
3. Select a destination `.aarec` file.
4. Review the storage preflight result. Recording is refused before the target is created when the
   destination is unwritable or already below the critical safe-capacity threshold.
5. Continue analyzing normally.
6. Watch the persistent **Experiment recording** strip.
7. Choose **File > Stop recording** or use the strip's Stop action.
8. Wait for finalization and read the completion summary. Confirm written frames/blocks, zero drops
   and gaps, the final state and the destination. Stop recording finalizes the recording; it does not
   by itself stop the underlying signal source.
9. Inspect the new file with **File > Inspect or recover recording...**. A normal new recording should
   report version 2 and `COMPLETE`. Preserve an incomplete result and its warning history if the run
   did not finish cleanly; a filename ending in `.aarec` is not proof of complete capture.

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

## Configurable storage policy

Preflight and runtime use the same `RecordingStoragePolicy`. The probe resolves the destination's
nearest existing parent, checks `FileStore.isReadOnly()` and writability, and obtains usable,
unallocated and total space. Expected growth includes normalized float samples and the container's
20-byte block header at the configured block size. Runtime uses the greater of expected and measured
rates, reserves the critical byte floor, and displays unknown capacity distinctly from zero space.

|           Java system property            |   Default |                      Meaning                       |
|-------------------------------------------|----------:|----------------------------------------------------|
| `audio.recording.storage.warningBytes`    | 268435456 | Warn below 256 MiB usable                          |
| `audio.recording.storage.criticalBytes`   |  67108864 | Stop below 64 MiB usable                           |
| `audio.recording.storage.warningSeconds`  |       300 | Warn below five minutes of safe recording          |
| `audio.recording.storage.criticalSeconds` |        30 | Stop below 30 seconds of safe recording            |
| `audio.recording.storage.safetyMargin`    |       0.1 | Additional duration margin in experiment preflight |

Both byte and time thresholds apply. Thresholds must be finite, non-negative and correctly ordered.
A failed query retains the last successful check time and appends a visible warning event; it is not
reported as a successful zero-space check. Capacity and source timestamps expose stale observations.
The persistent recording strip uses a red recording indicator plus text, storage severity and warning
history count. The final summary includes bytes, duration, drops/gaps, stop reason and warning history.

At critical capacity, the writer stops accepting new input, counts/discards queued blocks and writes
an **INCOMPLETE** footer while the reserved margin is still available. The marker participates in the
SHA-256, so changing it into a complete marker cannot pass checksum validation. Strict replay rejects
this file; inspection and non-destructive recovery remain available. Previous readers reject the new
incomplete marker safely while all valid existing complete version-2 recordings remain readable.

Swing preflight, recorder startup and finalization run on background workers. Runtime snapshots can
be rendered without filesystem access from either the capture thread or the Swing event thread.

For the complete microphone-array workflow, use
[Microphone experiments](microphone-experiments.md).

## Replaying a session

1. Inspect the source when its integrity is uncertain by choosing
   **File > Inspect or recover recording...**.
2. Choose **File > Open recording...** for a complete current recording or an accepted legacy
   recording.
3. The active capture service is stopped and replaced by a replay service.
4. Waveform, spectrum, spectrogram, phase, diagnosis and measurement panels process the replayed
   blocks as normal input.
5. Observe the signal until EOF. Reopen the same file to repeat the analysis. To compare parameter or
   software changes, retain the input file and record the configuration/version used in each pass.

Replay does not loop automatically. When the file is exhausted, replay stops. Reopen the file to run
it again.

The desktop **File > Open recording...** chooser accepts `.aarec` and legacy `.aar`. This path
replays a signal without restoring microphone geometry, a portable experiment document or a
collaborative graph. For array localization, select the matching profile and the intended replay
duration in [Recording replay](microphone-experiments.md#replay-and-recovery); its default five-second
limit can otherwise analyze only a prefix of a longer input.

## File identity

|        Property         |                 Current value                  |
|-------------------------|------------------------------------------------|
| Preferred extension     | `.aarec`                                       |
| Binary media type       | `application/vnd.carstenartur.audio-recording` |
| Current format version  | `2`                                            |
| Legacy import extension | `.aar`                                         |
| Legacy format version   | `1`                                            |

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
Consequently, the presence of the final target means clean container finalization, but the footer still distinguishes complete evidence from an explicitly incomplete stop.
The final filename alone does not prove completeness.

## Integrity states

Inspection distinguishes:

- `COMPLETE` — version 2 footer, counters and SHA-256 are valid;
- `INCOMPLETE` — the footer and checksum are valid, but low space, an early experiment stop,
  dropped blocks or a continuity gap prevented complete evidence;
- `LEGACY_UNVERIFIED` — a version 1 file ended cleanly at a block boundary but has no footer;
- `RECOVERABLE_INCOMPLETE` — complete version 2 blocks exist, but no footer was written;
- `TRUNCATED` — input ended inside a block or footer;
- `CORRUPT` — structural fields, footer counters or checksum are invalid.

Strict replay rejects incomplete, truncated and corrupt version 2 files. Inspection can count the
complete blocks without presenting them as successful evidence.

## Non-destructive recovery

1. Choose **File > Inspect or recover recording...** and select the original recording. For a remaining
   `.partial` file, use the chooser's **All Files** filter or enter its filename directly.
2. Read the integrity, format version, block/frame counts, continuity gaps and SHA-256. Save this
   inspection information with the original file before recovery.
3. For explicitly incomplete, recoverable, truncated or corrupt input, accept the offered recovery
   action and choose a **different** `.aarec` destination. Recovery copies complete readable blocks;
   an invalid header or an input with no complete blocks can prevent recovery.
4. Inspect the recovered file again. Its valid footer proves that the new container finalized; its
   frame indices and any continuity gaps still describe the original samples. Reopen it through
   **File > Open recording...** when it is a readable finalized file.
5. If using the recovered input in the microphone experiment workflow, import/select the new file
   and repeat **Check experiment readiness**. Any retained continuity gap remains a blocker there.

The source bytes are never modified. Recovery preserves frame indices, so it does not close a gap
caused by a dropped block. The standalone microphone workflow also offers recovery for
`LEGACY_UNVERIFIED` input so it can inspect a new verified version-2 container; the generic desktop
inspection dialog permits clean legacy replay directly and does not offer that conversion action.

Recovery proves the integrity of the new container; it does not recreate samples that were never
written. Provenance should therefore retain the original inspection result and source file digest in
an experiment evidence package. The managed microphone recovery path writes an inspection sidecar
and includes it in later replay evidence. The generic desktop recovery dialog does not assemble that
evidence package for you. See [Export and reopen evidence](microphone-experiments.md#export-and-reopen-evidence)
for profile restoration, inventory verification and the complete replay round trip.

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

