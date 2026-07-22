# Experiment operations and document interchange QA — 2026-07-22

## Scope

This review examines the current `master` after PRs #303 and #304, with emphasis on the parts of
Audio Analyzer that affect a real experiment operator rather than only algorithm correctness:

- continuous audio recording and storage safety;
- visible experiment readiness and runtime health;
- collaboration identity and participant presence;
- portable experiment setup exchange;
- plugin-specific document data and compatibility;
- import/export ergonomics, integrity and security.

The review is source-based and complements, rather than replaces, the existing automated reactor,
CodeQL, browser collaboration and screenshot checks. Those checks demonstrate that the implemented
behaviour is internally consistent; this review asks whether the current behaviour is the right
contract for experimental evidence.

## Executive conclusion

The repository has a strong technical foundation: deterministic workflow DSL and Git history,
reproducible execution snapshots, collaboration operation logs, calibrated microphone-array domain
objects, live/replay/simulation source abstractions and extensive automated tests.

It is **not yet safe to describe the complete experiment workflow as operationally finished**.
The largest remaining risks are not localization mathematics. They are evidence integrity,
operator visibility and identity trust:

1. The current recording tap is a best-effort UI poller, not a loss-aware recording pipeline.
2. Disk capacity and recording growth are not visible before or during recording.
3. A recording cannot prove clean finalization or complete source-frame continuity.
4. Collaboration identity is self-asserted and may be confused with an authenticated login.
5. The acoustic workbench remains visibly simulation-only despite the new live-source infrastructure.
6. There is no public, self-identifying experiment setup file or plugin document-section contract.

Issue #139 has therefore been reopened. Issues #305–#312 capture the implementation work identified
by this review.

## Direct decisions

### Should an operator see remaining disk space?

**Yes. This is required experiment readiness information, not an optional convenience.**

The operator should see more than a raw free-byte count:

- selected destination and whether it is writable;
- usable storage on the actual backing file store;
- current recording size;
- measured and expected growth rate;
- estimated safe recording time remaining;
- warning and critical thresholds;
- recording queue/backlog, dropped blocks and continuity gaps;
- last successful write and current completion/error state.

Free space is advisory and can change because of quotas, other processes or remote storage. The
application must still handle write failures. Nevertheless, an estimate is essential before starting
an intended-duration experiment and while it is running.

The recording payload uses normalized 32-bit floats regardless of the original device sample width.
Ignoring the small block overhead, approximate growth is:

```text
bytes/second = sampleRate × channels × 4
```

Examples:

- 48 kHz stereo: about 384,000 bytes/s or 1.38 GB/hour;
- 48 kHz, eight channels: about 1,536,000 bytes/s or 5.53 GB/hour;
- the historic 16 kHz stereo default: about 460.8 MB/hour.

That is large enough that a long array experiment can fail predictably on a nearly full volume. The
current UI gives no such warning.

### Should collaborators see one another's login names?

**Participants should be visible, but a login name must only be shown when it is server-verified and
allowed by privacy policy.**

Current collaboration identities are generated or edited in the browser. `actorId`, `userId` and
`displayName` are sent by the client and are not derived from an authenticated server principal.
Calling any of those values a login would be misleading.

The participant display should distinguish:

- public display name;
- optional verified account handle;
- anonymous/self-asserted pseudonym;
- owner/editor/viewer role;
- `You` marker;
- live/reconnecting/idle/offline state and last activity;
- per-client actor identity only in an expanded diagnostic view.

This is a security requirement as well as a UX improvement. Session ownership and actor-scoped
history operations currently rely on a client-provided actor identifier. Collaboration must remain a
trusted/demo feature until actor identity is server-bound.

### Should Audio Analyzer define a dedicated experiment file and media type?

**Yes.** The recommended initial public setup document is:

|     Property      |                        Proposed value                         |
|-------------------|---------------------------------------------------------------|
| Extension         | `.audioexp`                                                   |
| Media type        | `application/vnd.carstenartur.audio-analyzer.experiment+json` |
| Format identifier | `io.github.carstenartur.audio-analyzer.experiment`            |
| Envelope version  | `1`                                                           |
| Validation        | checked-in JSON Schema plus semantic validation               |

The exact spelling should be frozen before release. The important architectural decision is that this
is a small, inspectable, versioned setup document, not an audio container.

Audio recordings and datasets should be referenced by portable metadata and digest, or distributed in
a separately bounded evidence package. They must not be embedded as unbounded base64 fields.

A useful file taxonomy is:

- `.audioexp` — portable experiment setup and reproducibility contract;
- a reviewed non-conflicting extension such as `.aarec` — binary block recording; existing `.aar`
  remains a legacy import alias because `.aar` is already widely associated with Android Archives;
- a possible later evidence-package format — manifest plus bounded recordings/results/hashes.

### Should plugins contribute basic and plugin-specific document information?

**Yes, through a strict namespaced and versioned contract.**

The core envelope owns identity, format version, workflow payload, common profiles, plugin
requirements, output declarations and provenance. Each plugin owns only its namespaced `data`
section. A plugin section must declare separately:

- section identifier;
- parameter/schema version;
- algorithm behaviour version;
- JSON Schema and digest;
- required versus optional semantics;
- deterministic normalization;
- explicit migration paths;
- semantic validation diagnostics.

Plugin package version must not substitute for schema or algorithm compatibility.

The stable plugin API should remain framework-neutral. The host should own strict JSON parsing,
duplicate-key rejection, input limits, canonical serialization and schema evaluation. Plugins receive
only an already bounded immutable value tree or canonical JSON payload. Documents must never select a
Java class, script, command or ServiceLoader implementation.

## Detailed findings

### F-01 — Recording silently loses intermediate blocks

**Severity: critical for experiment evidence**

`RecordingTap` polls `AudioCaptureService.getLatestBlock()` with the same Swing refresh timer used for
visual updates. The current refresh interval is 200 ms. Capture blocks may be produced much faster.
Only the latest observed block is written; overwritten intermediate `latestBlock` values are not
queued or counted.

The recorder therefore cannot establish that a recording contains every source block. The final dialog
reports `blocksWritten`, but there is no expected count, frame continuity check or loss status.

**Action:** #305.

### F-02 — Disk I/O occurs on the Swing event-dispatch thread

**Severity: high**

The Swing `Timer` callback calls `writer.write(block)` synchronously. A slow local disk, network mount,
antivirus scan or quota event can freeze the entire UI. Repeated high-volume writes also compete with
rendering and input handling.

**Action:** #305 introduces a dedicated recorder worker and independent bounded subscription.

### F-03 — Recording failure is not persistently visible

**Severity: high**

An `IOException` is logged and the tap stops quietly. The user may continue the experiment believing
recording is active. Start and stop are reported through modal dialogs, but no persistent indicator
shows current recording state.

**Action:** #305 and #312.

### F-04 — No storage preflight or remaining-duration estimate

**Severity: high**

The selected target is opened/truncated immediately. The application does not verify writable storage,
show usable space or compare expected growth with an intended experiment duration before recording.
No runtime monitor warns before the file store is exhausted.

**Action:** #306.

### F-05 — Recording format cannot prove clean completion

**Severity: high for evidence; medium for diagnostic replay**

The current format has a versioned header and per-block records, but no completion footer, block/frame
summary or checksum. EOF after a complete block is accepted as normal. A process that stops between
blocks is therefore indistinguishable from a deliberately finalized file.

The reader detects a truncation inside a block, but there is no explicit recoverable/incomplete state,
no non-destructive recovery workflow and no atomic partial-to-final rename.

**Action:** #307.

### F-06 — `.aar` is an ambiguous public extension

**Severity: medium**

`.aar` is already established for Android Archive files. Existing Audio Analyzer recordings must
remain readable, but a future desktop association should use a distinct preferred extension.

**Action:** #307.

### F-07 — Real-world localization remains fragmented in the UI

**Severity: high for issue #139 acceptance**

The source and domain layers now support array profiles, calibration readiness and a JavaSound live
`MultiChannelAudioSource`. The visible `AcousticLocalizationWorkbenchPanel`, however, still labels
itself `simulation only` and creates only deterministic simulation scenarios. Imported recordings use
a different panel, while live localization is not selectable in the acoustic workbench.

A user cannot complete define → calibrate → record/replay/live → localize → benchmark → export as one
coherent workflow.

**Action:** #139 reopened with the remaining visible vertical slice.

### F-08 — Experiment health is fragmented and mostly retrospective

**Severity: high**

Calibration, synchronization, processing budget, export text, recording state and collaboration facts
live in separate panels, tabs, dialogs and logs. There is no single preflight decision or active-run
health snapshot.

**Action:** #312.

### F-09 — Collaboration identity is self-asserted

**Severity: critical before untrusted or networked production use**

The browser creates editable actor, user and display identifiers. REST requests carry these values and
the server uses actor identifiers for membership, ownership and actor-scoped history semantics. The UI
already shows participants, but it cannot truthfully identify a value as an authenticated login.

**Action:** #308.

### F-10 — Presence rendering exposes arbitrary attributes

**Severity: medium**

Remote presence currently renders arbitrary attribute key/value pairs directly. Presence should use a
small allow-listed projection for selected node, cursor, activity and status. This reduces accidental
personal-data exposure, unbounded UI content and future injection/ergonomics risks.

**Action:** included in #308.

### F-11 — No self-identifying portable experiment setup

**Severity: high for interoperability and reproducibility**

The workflow DSL is deterministic and appropriate for Git, but starts directly with `workflow` and has
no public envelope identity/version/media type. The localization experiment codec is an internal
properties representation. `.aar` contains samples only. Evidence exports are directories.

**Action:** #309.

### F-12 — Core workflow metadata is too weak for public plugin interchange

**Severity: high**

`Metadata` is a `Map<String,String>`, which is useful for internal searchable annotations but cannot
provide typed nested plugin parameters, independent schema versions, migrations or detailed validation
paths. A portable format must not turn every plugin field into an unvalidated string convention.

**Action:** #310.

### F-13 — Plugin contributions advertise formats but cannot process document sections

**Severity: high**

`ExperimentContribution` and `ExportFormatContribution` are metadata/identity contracts. They do not
provide schema, normalizer, validator, migration or unknown-data preservation behaviour for experiment
documents.

**Action:** #310.

### F-14 — No safe file import/apply preview or desktop association

**Severity: medium until the format exists; high afterward**

A shared setup file needs more than parsing. The receiver must see semantic changes, required plugins,
migrations, hardware bindings and security warnings before current state changes. Desktop association
must open the preview, not execute the experiment.

**Action:** #311.

### F-15 — Local output paths are not portable

**Severity: medium**

The current experiment metadata catalogue includes an `output.path` concept. That can remain useful as
local runtime state, but it must not be copied into a portable setup file as an absolute machine path.
The portable document should declare logical outputs and basenames; the receiver selects the local
output root.

**Action:** #309 and #311.

### F-16 — Public import must not reuse permissive internal properties parsing

**Severity: medium**

The current localization manifest codec uses Java `Properties` and URL-encoded values. It is
appropriate as an internal deterministic codec, but it lacks the explicit public schema, duplicate-key
policy, structured typing and bounded parser contract required for untrusted interchange.

**Action:** #309 defines a separate public JSON format. Existing internal codecs need not be removed.

## Required displays

### 1. Experiment readiness header

Always visible before a run:

- overall `BLOCKED`, `READY WITH WARNINGS` or `READY` state;
- experiment document/path, dirty state, version and hash;
- selected source mode and source availability;
- profile/layout/channel-map status;
- calibration age, expiry and timing uncertainty;
- plugin compatibility and workflow validation;
- output/storage readiness;
- direct navigation to every blocker.

### 2. Recording strip

Visible throughout any recording:

- recording indicator;
- elapsed time;
- destination filename;
- bytes/file size and write rate;
- usable storage and estimated safe time remaining;
- received/written/dropped frames and blocks;
- queue backlog;
- last successful write;
- complete/incomplete/error state;
- explicit Stop/finalize action.

### 3. Source and signal health

- device connected/disconnected and last block age;
- sample rate/format/channel count;
- clipping and prolonged silence;
- channel activity/gain/polarity anomalies;
- ring/subscriber backlog and realtime overruns;
- synchronization/calibration degradation;
- stale-data indication.

### 4. Experiment lifecycle progress

- define, calibrate, record, localize, benchmark and export stages;
- current stage and responsible operator;
- bounded progress and estimated remaining time when meaningful;
- cancellation/finalization semantics;
- warning timeline.

### 5. Collaboration participants

- display name and `You` marker;
- owner/editor/viewer role;
- verified handle only when authenticated and permitted;
- anonymous/unverified indicator;
- live/reconnecting/idle/offline and last activity;
- optional current allow-listed activity;
- internal identifiers only in diagnostics.

### 6. Import preview

- format/version/hash and provenance;
- semantic workflow diff;
- required/missing plugins and migrations;
- profiles, calibration and source references;
- unknown preserved sections;
- local bindings still required;
- security warnings;
- Open/Import read-only/cancel decision.

### 7. Post-run completeness summary

- complete/incomplete/failed reason;
- expected versus written blocks/frames;
- recording integrity and checksum;
- capacity/loss/warning history;
- calibration/plugin/algorithm versions actually used;
- generated and missing outputs with hashes;
- final document/evidence hash and destination.

## Recommended implementation order

### Phase 1 — Prevent invalid evidence

- Issue #305 — loss-aware non-blocking recording.
- Issue #306 — storage preflight and persistent capacity display.
- Issue #307 — recording finalization, integrity and recovery.
- Issue #308 — server-bound collaboration identity before untrusted deployment.

### Phase 2 — Complete the operator workflow

- Issue #139 — one simulation/replay/live acoustic workflow.
- Issue #312 — shared readiness and runtime-health model/UI.

### Phase 3 — Portable experiment exchange

- Issue #309 — public experiment document and schema.
- Issue #310 — plugin sections, validation and migrations.
- Issue #311 — safe import/export and desktop association.

The phases may overlap at API-design level, but a polished file association should not precede strict
format and plugin validation, and a polished dashboard should not hide a lossy recorder.

## Issue map

| Issue |                             Purpose                              |
|-------|------------------------------------------------------------------|
| #139  | complete visible simulation/replay/live localization workflow    |
| #305  | loss-aware, non-blocking and observable recording                |
| #306  | disk capacity, growth and remaining safe duration                |
| #307  | recording integrity, preferred extension and partial recovery    |
| #308  | authenticated actor identity and trustworthy participant display |
| #309  | portable `.audioexp` document and media type                     |
| #310  | versioned plugin document sections and migrations                |
| #311  | safe import/export and desktop association                       |
| #312  | experiment readiness and runtime health dashboard                |

## Exit criteria for the next QA closure

The real-world experiment workflow should not be declared complete again until a deterministic test and
generated screenshot demonstrate this path:

1. open or create a portable experiment setup;
2. inspect required plugins and bind local resources;
3. select simulation, replay or live source in one workbench;
4. validate array mapping and calibration readiness;
5. preflight recording destination and intended duration;
6. run with persistent source/recording/storage/synchronization health;
7. deliberately simulate slow storage, dropped data, device disconnect and low disk space;
8. stop/finalize with explicit complete or incomplete evidence status;
9. export a reproducibility package and reopen the setup on a second clean installation;
10. verify participant attribution in both anonymous and authenticated modes.

Passing unit tests remains necessary, but closure requires observable evidence that the user can detect
and understand every condition that can invalidate an experiment.
