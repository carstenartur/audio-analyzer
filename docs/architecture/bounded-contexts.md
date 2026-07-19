# Bounded contexts

This document defines the current semantic and technical boundaries of Audio Analyzer. A bounded context owns its terminology, invariants and public contracts. Cross-context communication uses explicit APIs or adapters; implementation packages are not shared by convenience.

## Context map

```text
Audio Processing ───────────────┐
                               ├──> Desktop Presentation
Plugin Extension ──────────────┤
                               └──> Experimental Acoustic Research

Workflow Modeling ──> Validation ──> Execution
        │
        ├──> Collaboration ──> Web Presentation
        │          │
        │          └──> Ordered Events / Outbox
        │
        └──> Versioning and Persistence
                   │
                   └──> future rebuildable Search Projection
```

Dependency arrows point from a consumer to an allowed upstream contract. Infrastructure adapters implement ports owned by stable contexts; stable contexts never import infrastructure implementations.

## Overview

| Context | Primary modules | Status | Authority |
|---|---|---|---|
| Audio Processing | `audio-core`, `audio-geometry`, `audio-acquisition`, `audio-dsp` | Stable foundation | Audio samples, formats, DSP and analysis snapshots |
| Workflow Modeling | `audio-core` | Stable foundation | Immutable workflow graph and semantic operations |
| Validation | `audio-core` | Stable foundation | Structural and semantic workflow acceptance |
| Execution | `audio-core` contracts; concrete backend work remains | Partial | Immutable run model; current dry run is simulation only |
| Collaboration | `audio-core`, `audio-app` | Implemented | Session membership, operation order, revision, history and undo eligibility |
| Versioning and Persistence | `audio-core` facade, `audio-app`, shared JGit library | Implemented | Durable sessions/outbox and versioned workflow checkpoints |
| Plugin Extension | `audio-plugin-api`, host adapter in `audio-app` | Stable API foundation | Host-visible contribution metadata |
| Presentation | `audio-app`, `audio-web-editor` | Implemented | Rendering and user intent only |
| Experimental Acoustic Research | `audio-experimental-acoustic` | Experimental | Simulation and research models inside its scope |

## 1. Audio Processing

### Responsibility

Own framework-independent audio values and signal-processing behavior:

- immutable `AudioBlock` data;
- format descriptors and sample clocks;
- bounded buffering;
- sample decoding;
- deterministic generators;
- DSP processors and pipelines;
- FFT, spectrum, spectrogram and measurement analysis;
- recording/replay format;
- broad stereo delay diagnostics.

### Modules and package roots

```text
audio-core
  org.hammer.audio.core
  org.hammer.audio.buffer
  org.hammer.audio.snapshot

audio-geometry
  org.hammer.audio.geometry

audio-acquisition
  org.hammer.audio.acquisition

audio-dsp
  org.hammer.audio.capture
  org.hammer.audio.dsp
  org.hammer.audio.analysis
  org.hammer.audio.diagnosis
  org.hammer.audio.localization
  org.hammer.audio.recording
  org.hammer.audio.signal
  org.hammer.audio.spectrogram
```

### Rules

- No Swing, Spring, React, JGit, Hibernate or Playwright imports.
- No dependency on experimental acoustic implementations.
- Analysis produces immutable snapshots rather than pixel output.
- Recording persists audio-domain values without depending on workflow UI state.

## 2. Workflow Modeling

### Responsibility

Own the immutable design-time graph and semantic edit vocabulary:

- `Workflow`, nodes, ports, edges and metadata;
- typed port compatibility;
- stable semantic identifiers;
- catalog descriptions;
- deterministic workflow DSL;
- `WorkflowOperation` subtypes and complete reconstructible bodies.

### Module and package roots

```text
audio-core
  org.hammer.audio.workflow
  org.hammer.audio.workflow.catalog
  org.hammer.audio.workflow.dsl
```

### Rules

- No audio sample computation in the workflow graph.
- No browser or Swing state in nodes, edges or DSL.
- No JGit, Hibernate, Spring or HTTP types in public contracts.
- Semantic operations describe domain intent, not component events.
- The legacy in-memory operation log is not the collaborative durable undo authority.

## 3. Validation

### Responsibility

Validate the exact immutable workflow or operation candidate before acceptance or execution:

- duplicate and dangling identifiers;
- port direction, multiplicity and data-type compatibility;
- cycle and structural constraints;
- operation-specific preconditions;
- resolved merge or run input validity.

### Ownership

Validation currently lives with workflow contracts in `audio-core`. It may be extracted into a dedicated package when its public vocabulary grows.

### Rules

- Validation results are deterministic and structured.
- Presentation may explain violations but must not redefine them.
- Infrastructure failures are not disguised as workflow validation failures.

## 4. Collaboration

### Responsibility

Own live multi-user workflow editing:

- immutable session mode;
- owner and participant membership;
- actor identity and authorship;
- accepted semantic revision and ordered event sequence;
- idempotent operation/command identity;
- personal and explicit shared undo/redo policy;
- blocker and conflict analysis;
- durable history/capability queries;
- session recovery.

### Modules and package roots

```text
audio-core
  org.hammer.audio.workflow.collaboration
  framework-independent collaboration/store ports

audio-app
  HTTP and SSE adapters
  Hibernate collaboration/outbox adapters
  Spring lifecycle and scheduling configuration
```

### Authority rules

- The server owns canonical graph state and semantic eligibility.
- Presence is transient actor-scoped data, not workflow state.
- SSE transports accepted events; it is not an undo stack.
- Undo and redo append new audited semantic operations.
- A cached browser row or preview never overrides expected-revision validation.
- Rejected commands append no operation, revision, event or outbox residue.

See:

- [React Flow session client](react-flow-session-client.md)
- [Durable semantic undo and redo](semantic-undo-redo.md)
- [Two-browser collaboration evidence](../collaboration-e2e.md)

## 5. Versioning and Persistence

### Responsibility

Own durable infrastructure behind framework-independent ports:

- deterministic workflow checkpoint load/save;
- Git object, ref and history storage through `jgit-storage-hibernate-core`;
- durable collaboration sessions and complete operation bodies;
- transactional outbox rows and leased delivery;
- versioned schema migration and Hibernate validation;
- file-system fallback for explicit local/test use.

### Boundary

```text
Workflow/Collaboration ports
  -> audio-app infrastructure adapters
  -> one DataSource and one SessionFactory
       shared JGit entities
       Audio Analyzer collaboration/outbox entities
  -> database
```

### Authority rules

- Workflow DSL in Git and durable collaboration rows are authoritative in their documented scopes.
- The outbox is durable transport work, not a second workflow history.
- Future search indexes are rebuildable derived state.
- Audio Analyzer does not copy generic JGit DDL or internal storage code.
- Production startup uses versioned migrations followed by `validate`, not implicit schema update.
- Tests use the shared persistence path, not raw-JDBC alternatives.

See [Hibernate-backed workflow persistence](../workbench-hibernate-persistence.md).

## 6. Execution

### Responsibility

Own immutable run input and lifecycle contracts:

- `ExecutionSnapshot`;
- `ExecutionPlan`;
- `ExecutionContext`;
- `ExecutionResult`;
- deterministic run/provenance identifiers;
- future backend and cancellation ports.

### Current status

The current `SnapshotExecutionService` dry run orders nodes and changes statuses. It is useful for contract testing but is not actual audio computation.

Epic #248 and issues #273–#275 own truthful run orchestration, a real deterministic audio backend and production run UX.

### Rules

- A run captures immutable input before dispatch.
- Later editor changes cannot mutate a run.
- Simulation and actual computation must be explicitly distinguished.
- Concrete DSP executors do not leak into the workflow domain API.

## 7. Plugin Extension

### Responsibility

Own stable host-facing extension contracts:

- plugin descriptor;
- analyses and demo signals;
- signal sources and experiments;
- pipelines and snapshot streams;
- visualization metadata;
- calibration, benchmarks and exports;
- menu actions and optional Swing views.

### Module and package root

```text
audio-plugin-api
  org.hammer.audio.plugin
```

### Rules

- The API does not depend on concrete audio or application modules.
- Plugins register `AudioAnalyzerPlugin` through `ServiceLoader`.
- The host loads providers independently and records individual failures.
- A contributed Swing factory returns a fresh component per invocation.
- Prefer UI-independent contributions for web/headless reuse.

See [Plugin development](../development/plugin-development.md).

## 8. Presentation

### Desktop presentation

`audio-app` Swing packages render audio snapshots, manage user interaction, open plugin views and create exports.

```text
org.hammer
org.hammer.audio.ui
org.hammer.audio.ui.theme
```

### Web presentation

`audio-web-editor` contains React/TypeScript source and the reproducible Vite production assets packaged by `audio-app`.

The web client:

- renders canonical workflow projections;
- converts gestures into typed commands;
- displays server-reported history and conflicts;
- manages transient dialog, selection and viewport state;
- reconciles on revision/event gaps.

### Rules

- Presentation may depend on stable contracts and application APIs.
- Stable contexts never import presentation code.
- React component state and Yjs are not canonical semantic workflow state.
- UI tests use stable selectors and production adapters rather than test-only semantic endpoints.

## 9. Experimental Acoustic Research

### Responsibility

Own research-grade localization and dataset functionality:

```text
audio-experimental-acoustic
  org.hammer.audio.experimental.acoustic.*
```

Current areas include simulation, microphone arrays, TDOA, beamforming, tracking, wingbeat features, datasets and benchmark metrics.

### Rules

- Experimental assumptions do not migrate into stable APIs without evidence and review.
- Simulation results are not presented as calibrated hardware performance.
- Real microphone-array claims require synchronization, geometry calibration and error budgets.
- Plugin descriptor and documentation mark experimental status truthfully.

## Verification boundary

The architecture is protected by:

- Maven dependency direction;
- source/import boundary tests;
- ArchUnit fitness tests;
- TypeScript architecture lint;
- framework-independent operation/history reducers and codecs;
- Hibernate migration/recovery tests;
- packaged two-browser collaboration tests;
- generated screenshot integration tests.

A test adapter may depend on Playwright/Testcontainers. Production domain code may not.

## Change checklist

Before adding a dependency or public type:

1. Identify the context that owns the invariant.
2. Put the port with the owning stable context.
3. Put the implementation in an adapter/infrastructure module.
4. Confirm no UI or persistence type leaks into the contract.
5. Add an architecture test when the boundary is important.
6. Update this map and the high-level architecture when ownership changes.
