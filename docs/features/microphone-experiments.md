# Microphone experiments: setup, readiness, runtime and evidence

The maintained browser route is **`/workbench/experiment`**. Open it from **Open microphone experiment
workflow** in the workbench. The desktop entry point is **Experiment > Microphone-array workflow…**
in `ExperimentAudioAnalyseFrame`. Both views call `MicrophoneExperimentService` and display the same
immutable health model. They reuse the established acquisition, recorder and localization pipeline.

## Complete a first simulation

1. Leave **Source mode** on **Simulation** and select the rectangular example profile.
2. Choose a deterministic scenario and an intended duration. Keep **Record every consumed audio
   block** enabled to preserve the generated input as well as the configuration.
3. Open **Edit geometry, device format and calibration** if you need different microphone positions
   or channel counts. Positions are measured in metres; channels are contiguous from zero. Added
   channels require measured, distinct positions. Save the profile before continuing.
4. Choose **Check experiment readiness**. Review the source format, profile, calibration applicability,
   recording destination, usable storage, expected growth and safe-duration estimate.
5. **Start experiment** becomes available only after a ready preflight of the current saved choices.
   A true blocker must be resolved. A storage-margin warning requires explicit acknowledgement.
6. Watch the persistent strip, readiness findings, microphone/track map, processing budget, recorder
   queue and dropped/gap counts. **Stop & finalize** ends acquisition; stopping before completion
   retains an incomplete experiment. Pause is deliberately unavailable for continuous capture.
7. Use **Export complete evidence** or **Export incomplete evidence**, as labelled. The evidence ZIP
   contains the actual recording when requested, complete profile/configuration manifest,
   localization CSV, summary, health facts/history and `SHA256SUMS`. Simulation also includes the
   benchmark report against its known scenario. Live and replay never receive simulated ground truth.

The displayed **Recording file SHA-256** and health fact `recording.sha256` identify the entire exported
recording, including its footer, and agree with its `SHA256SUMS` entry. The separate
`recording.verifiedContentSha256` fact is the format's verified checksum of the header, blocks and
completion marker, stored in the footer. These digests cover different bytes and must not be compared
as if they had the same scope.

The packaged-browser scenario captures `microphone-experiment-ready.png` for this state.

## Bind a real array and measured calibration

Choose **Live acquisition**. Device discovery lists input-capable devices on the **application host**;
a browser microphone is not a host JavaSound device. An empty list is a real blocker. Connect a
synchronized multichannel device and refresh the workflow, then bind its descriptor to the profile.
Choose the device's PCM sample rate, channel count, bit depth, sign and byte order. Preflight checks
advertised format support. Permission, exclusive access and disconnection can still fail when the
line is opened or read; those failures remain visible with incomplete evidence.

The profile editor accepts measured geometry and calibration. The browser measurement rows have:

```text
channel, offset samples, drift ppm, residual RMS samples, jitter RMS samples, gain, inverted polarity
```

Supply one row per channel. The simple form uses channel 0 as the reference at frame 0, with zero
reference offset and drift. Supply a calibration identity and measurement/expiry times in UTC.
Residual/jitter values must be non-negative, gain must be positive, and polarity is `true` or `false`.
The advanced portable profile supports other reference frames/channels. The desktop form exposes
these measurements in an editable table. The application does not invent calibration measurements.

Changing geometry, channel mapping, capture format or device clears the form's calibration. When an
existing stored profile changes geometry or device settings, the service rejects reuse of the old
measurement identity/time. Save a newly measured calibration or remove it and remain blocked for
live acquisition. Live timing readiness uses the existing one-sample uncertainty budget and rejects
expired or mismatched calibration. Simulation does not apply physical-device timing corrections to
ideal simulated samples.

The packaged-browser scenario captures `microphone-experiment-blocked.png` for the unavailable-host-device state.

## Replay and recovery

Choose **Recording replay** and import a `.aarec`, legacy `.aar` or `.partial` file, or select a recording
from the managed run history. Imports stream to disk with a 512 MiB request limit and are inspected
before becoming selectable. The source's channel count must match the selected array profile.
Strict experiment replay requires a complete, verified recording with no continuity gaps.

For incomplete input, **Recover incomplete input into a new file** preserves the original and writes
a separate recording containing readable blocks. Recovery does not recreate lost samples. A recovery
sidecar retains the original inspection and is included when the recovered input is exported again.
The replay source streams and re-chunks blocks, including the final partial block, through the same
tracking pipeline as simulation and live capture. Benchmark output is unavailable without external
ground truth; the UI states that explicitly.

## Read health without losing context

See [experiment readiness and runtime health](experiment-health.md) for document/workflow correlation,
authorized session checks and the exact scope of each shared projection.

The shared states are `NOT_CONFIGURED`, `BLOCKED`, `READY_WITH_WARNINGS`, `READY`, `RUNNING`,
`DEGRADED`, `STOPPING`, `COMPLETED`, `INCOMPLETE` and `FAILED`. Only the two ready states permit a start,
and the server rechecks the exact immutable profile/configuration before scheduling execution.

Findings carry stable codes, severity, observation time, freshness and a remediation action. Runtime
facts include source format and last sample time, channel RMS and clipping, synchronization quality,
processing duration/budget and overruns, recording queue/drop/gap counts, storage state and actual
plugin version. Stale live observations become warnings. Warnings remain in history after a healthy
refresh. Acknowledgement preserves the condition and operator action; it cannot remove a blocker.
A recurrence after recovery requires a new acknowledgement. Unauthenticated actions are labelled
**Unattributed local operator**. Authenticated acknowledgements use **Authenticated operator
(identity withheld)**; raw account handles are never included by this standalone workflow.

This route's readiness scope is `STANDALONE_MICROPHONE`. Its preflight fingerprint covers the saved
microphone profile and all run choices. It does not execute an open `.audioexp` document or the
editable workflow graph, so document dirty state, document migrations and collaboration revision are
explicitly not applicable here. The workflow workbench reports those checks through the same health
model for its own selected document/revision. Local bindings checked for an import candidate do not
silently become execution bindings for another snapshot.

**Export diagnostic snapshot** provides the visible facts and warning history. A failed run keeps its
initial profile manifest and an explicit incomplete evidence status. Evidence download is unavailable
if disk finalization cannot persist a truthful final manifest and hash inventory.

The browser scenario captures `microphone-experiment-complete.png` after downloading and inspecting
the actual simulation evidence. Fixed-time typed health fixtures additionally capture
`microphone-experiment-recording.png`, `microphone-experiment-degraded.png` and
`microphone-experiment-incomplete.png`; these test rendering and controls without requiring a
particular CI disk failure or connected microphone.

## Storage location and verification scope

Profiles, imports and per-run evidence live beneath `workbench.experiments.directory`; the default is
`${user.home}/.audio-analyzer/experiments`. Run IDs and recording references are local to that managed
directory. The browser never accepts arbitrary host input paths.

`MicrophoneExperimentWebIT` runs the packaged application in Testcontainers and exercises saved
profile edits, live blockers, a deterministic 16,000-frame simulation, all six screenshot states and
a downloaded evidence ZIP. Unit/behavior tests cover storage failures, incomplete footer reopening,
strict replay rejection, exact configuration checks, source equivalence, cancellation and health
latching. See [recording and replay](recording-and-replay.md) for storage thresholds and format details.

Physical device permission, actual channel wiring, hardware drift, real room localization accuracy
and long-duration device stability require a connected array and measured reference data. A passing
simulation or browser scenario does not establish those physical results. Record the device, driver,
geometry, calibration, environment and measured reference positions when performing that validation.
