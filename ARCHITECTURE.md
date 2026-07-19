# Audio Analyzer architecture

Audio Analyzer is a layered Java 21 platform for signal inspection, reproducible audio experiments and versioned workflow design. It exposes two user surfaces—a Swing signal workbench and a browser-based workflow workbench—over framework-independent audio and workflow models.

The central rule is that UI, transport, persistence and experimental research code must not become the authority for stable audio or workflow semantics.

## Architectural goals

The architecture supports:

- deterministic audio-processing experiments without mandatory hardware;
- live and recorded signal inspection through the same domain model;
- immutable snapshots for analysis and export;
- visual workflow design without moving business rules into the browser;
- multi-user collaboration with explicit revision and undo semantics;
- versioned workflow checkpoints in Git-compatible storage;
- optional durable Hibernate persistence and transactional outbox delivery;
- plugin-provided experiments without host dependencies on concrete plugins;
- honest separation between stable platform behavior and experimental localization research.

## User surfaces

### Desktop signal workbench

The Swing application focuses on audio data:

```text
capture or deterministic generator
  -> AudioBlock
  -> bounded buffer / recording boundary
  -> DSP pipeline
  -> analyzers
  -> immutable snapshots
  -> waveform / spectrum / spectrogram / measurements / exports
```

DSP and analysis code operate on normalized domain data, not pixels or Swing components.

### Web workflow workbench

The React Flow application focuses on workflow structure and collaboration:

```text
browser gesture
  -> typed semantic command
  -> session, actor, mode and expected-revision validation
  -> canonical workflow operation
  -> durable session append
       operation body
       deterministic workflow projection / DSL
       semantic revision and event sequence
       transactional outbox event
  -> accepted projection
  -> ordered SSE to connected browsers
```

React Flow renders the canonical projection and captures intent. It is not a second workflow engine, undo stack or persistence layer.

## Maven modules

```text
audio-core
audio-geometry
audio-acquisition           -> audio-core, audio-geometry
audio-dsp                   -> audio-core
audio-plugin-api            stable host contracts; no audio-* dependencies
audio-experimental-acoustic -> stable audio modules + audio-plugin-api
audio-web-editor            React/TypeScript source and packaged static assets
audio-app                   -> stable modules; Swing host and Spring Boot workbench
                               runtime: optional experimental plugin
```

The opt-in `workbench-screenshot-tests` module is added only by the `screenshot-tests` Maven profile. It exercises the packaged product with Testcontainers and Java Playwright without adding Docker to the normal reactor.

### Responsibilities

- `audio-core` owns immutable audio types, ring-buffer contracts, workflow models, deterministic workflow operations and execution-domain contracts.
- `audio-geometry` owns reusable 2D geometry and localization constraints.
- `audio-acquisition` owns microphone metadata, channel/array descriptions, sources and clocks.
- `audio-dsp` owns decoding, processors, FFT, analyzers, diagnosis and the `.aar` recording format.
- `audio-plugin-api` owns stable host-facing extension contracts.
- `audio-experimental-acoustic` owns localization, dataset, simulation and tracking research.
- `audio-web-editor` owns the maintained React Flow client and reproducible production assets.
- `audio-app` owns Swing rendering, JavaSound/demo wiring, Spring HTTP/SSE adapters, persistence integration, exports and plugin hosting.

Detailed package ownership is documented in [`docs/architecture/bounded-contexts.md`](docs/architecture/bounded-contexts.md).

## Core audio model

`AudioBlock` is the central audio value. It carries normalized samples with frame and timing metadata and can cross capture, DSP, recording and analysis boundaries without UI dependencies.

`AudioFormatDescriptor` describes sample rate, channel count and sample format independently of JavaSound.

`AudioRingBuffer` provides bounded producer/consumer decoupling. Analysis components publish immutable snapshots rather than mutating the source block.

Important stable components include:

- sample decoding;
- deterministic generators;
- composable DSP processing;
- FFT and spectrum snapshots;
- RMS, peak, clipping and dominant-frequency measurements;
- spectrogram history;
- diagnostic findings;
- recording and replay.

## Workflow domain

Design-time workflows and runtime execution are separate:

```text
Workflow
  -> ExecutionSnapshot
  -> ExecutionPlan
  -> ExecutionContext
  -> ExecutionResult
```

A workflow is immutable and framework-independent. Visual edits are represented by semantic operations such as:

- create a node;
- connect or disconnect typed ports;
- change a property;
- rename or delete a semantic object where supported.

The legacy in-memory `WorkflowOperationLog` remains useful for deterministic domain replay. Collaborative undo does not call its destructive “remove last” behavior.

## Collaboration authority

A collaboration session has an immutable mode:

- `PRIVATE_WORKSPACE`;
- `SHARED_SESSION_PERSONAL_UNDO`;
- `SHARED_SESSION_SHARED_UNDO`.

The server owns:

- membership and actor identity validation;
- semantic revision and event sequence;
- accepted operation ordering;
- canonical workflow projection;
- current undo/redo capabilities;
- conflict and blocker analysis;
- command idempotency;
- session recovery.

The browser owns transient interaction state such as selected controls and viewport. Presence is transported separately and never enters workflow history or reproducibility input.

## Durable semantic undo and redo

Undo and redo are append-only semantic commands.

```text
history capability query
  -> explicit target
  -> fresh preview
  -> expected-revision validation
  -> new inverse operation
  -> canonical append
```

The system preserves:

- original operation;
- undo or redo command kind;
- stable client command id;
- target relationship;
- complete reconstructible operation body;
- actor, time, revision and affected object ids.

Later operations that touch affected objects block an unsafe inverse, including later operations by the same actor.

See [`docs/architecture/semantic-undo-redo.md`](docs/architecture/semantic-undo-redo.md).

## Ordered events and recovery

SSE provides ordered accepted events and live presence after a sequence cursor. It is not an authoritative undo stack.

The client reducer:

- ignores duplicate sequences;
- applies the next contiguous event;
- reconciles on a non-snapshot gap;
- accepts a canonical snapshot as replacement state;
- reconnects from the last accepted sequence.

On full page reload, session storage may restore actor and active-session identity. The client then rejoins and reloads the canonical session projection. A legacy orientation request is not allowed to overwrite a session that became active while that request was in flight.

## Persistence and versioning

The default workbench uses memory mode.

Durable mode uses:

- one Spring-managed `DataSource`;
- one application-owned Hibernate `SessionFactory`;
- `jgit-storage-hibernate-core` for generic Git object/ref storage;
- Audio Analyzer entities for collaboration sessions, operations and outbox rows;
- separate ordered Flyway migration histories by schema owner;
- Hibernate schema validation after migration.

Authoritative data is split deliberately:

- live collaboration truth: durable session/operation state and canonical workflow projection;
- version history: deterministic workflow DSL committed through `VersionedWorkflowStore`;
- transport work: transactional outbox rows;
- future search: rebuildable derived projections, never authority.

A live operation and its outbox row share one Hibernate transaction. A Git checkpoint remains a separate explicit application command unless a verified shared transaction contract says otherwise.

See [`docs/workbench-hibernate-persistence.md`](docs/workbench-hibernate-persistence.md).

## Plugin architecture

`audio-plugin-api` is isolated from the host and concrete audio modules. `AudioAnalyzerPlugin` implementations are discovered through `ServiceLoader`.

A plugin may contribute:

- analyses and demo signals;
- signal sources and experiments;
- processing pipelines and snapshot streams;
- visualization descriptions;
- calibration and benchmark definitions;
- exports, menu actions and optional Swing views.

The host loads each plugin independently so one invalid provider does not prevent other plugins from loading.

See [`docs/development/plugin-development.md`](docs/development/plugin-development.md).

## Experimental acoustic localization

`audio-experimental-acoustic` contains simulation, TDOA, beamforming, tracking, wingbeat and dataset research. Its assumptions remain isolated from stable APIs.

Real localization requires evidence for:

- synchronized multichannel capture;
- array geometry calibration;
- acoustic propagation and reflection effects;
- clock offset and drift;
- confidence and error budgets;
- repeatable benchmark fixtures.

No architectural boundary turns an experimental algorithm into a validated detector.

## Boundary enforcement

The build enforces architecture through:

- Maven module dependencies;
- source/import boundary tests;
- ArchUnit fitness tests;
- TypeScript architecture lint for the web client;
- framework-independent reducer and codec tests;
- migration/schema-validation integration tests;
- packaged two-browser tests;
- generated screenshot verification.

Stable domain packages must not import Swing, Spring, JGit, Hibernate, Playwright or experimental implementation packages.

## Current open architecture work

The implemented collaboration foundation does not complete every planned workflow capability. Current open slices include:

- durable full-process browser restart evidence (#249);
- semantic diff, three-way merge and conflict resolution (#246);
- rebuildable workflow-history search (#247);
- immutable actual workflow execution and run UX (#248, #273–#275).

See [`ROADMAP.md`](ROADMAP.md).

## Extension guidelines

When adding functionality:

1. Put stable reusable semantics in a framework-independent module.
2. Treat UI state as an adapter, not canonical domain state.
3. Add a typed command or port instead of importing an infrastructure implementation.
4. Keep derived indexes and event transports rebuildable or replayable.
5. Keep experimental assumptions in an experimental module.
6. Add deterministic tests before release-facing claims.
7. Update executable documentation and screenshots when visible behavior changes.

