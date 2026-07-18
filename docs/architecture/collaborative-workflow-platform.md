# Collaborative Workflow Platform Architecture

Status: Active architecture and delivery map  
Primary decisions: [`adr-006-versioned-collaborative-workflow-store.md`](adr-006-versioned-collaborative-workflow-store.md) and [`adr-007-editor-stack.md`](adr-007-editor-stack.md)

## Purpose

This document describes how workflow graphs are drawn, edited, versioned, shared and executed. It connects the accepted domain, persistence and editor decisions into one scenario-level architecture.

The accepted ADRs and bounded-context rules are normative if this document becomes stale.

## Existing foundation

Audio Analyzer already provides:

- immutable design-time workflows in `audio-core`;
- semantic `WorkflowOperation` edits;
- `WorkflowOperationLog` for deterministic replay and inverse-operation primitives;
- typed validation through `WorkflowValidator`;
- deterministic workflow DSL serialization;
- `VersionedWorkflowStore` backed by released `jgit-storage-hibernate` infrastructure;
- durable collaboration sessions, restart recovery, transactional outbox dispatch, versioned migrations and safe retention;
- ordered SSE event transport with bounded replay;
- React Flow selected by ADR-007 as the production browser adapter.

The collaborative editor extends this foundation and must not introduce a second workflow model.

## Target architecture

```text
audio-web-editor (React Flow)
    -> workflow HTTP command API
    -> ordered SSE projection/events
    -> workflow application services
    -> WorkflowOperationLog
    -> audio-core Workflow + WorkflowValidator
    -> deterministic workflow DSL
    -> VersionedWorkflowStore
    -> jgit-storage-hibernate 0.1.5+
    -> shared Hibernate SessionFactory / database
    -> transactional outbox
    -> optional broker adapters and connected clients
```

## Sources of truth

|            Concern             |          Source of truth           |                       Notes                        |
|--------------------------------|------------------------------------|----------------------------------------------------|
| Semantic graph                 | `audio-core Workflow`              | Framework-independent and server-validated.        |
| Accepted edit order            | Durable semantic operation history | Used for replay, audit and collaboration recovery. |
| Current collaboration revision | Durable session aggregate          | Browser projections are disposable.                |
| Durable version                | JGit commit from deterministic DSL | Checkpoints, not every pointer movement.           |
| Event delivery state           | Transactional outbox               | At-least-once delivery with stable event IDs.      |
| Presence                       | Server session presence state      | Cursor and connection state are not workflow data. |
| Rendering/layout               | React Flow adapter state           | Local layout may be replaced at any time.          |
| Execution input                | Immutable `ExecutionSnapshot`      | A running job is isolated from later edits.        |

## Editor boundary

`audio-web-editor` is the single maintained production frontend.

React Flow owns:

- node, edge and typed-handle rendering;
- selection, viewport and local node positions;
- translation of gestures into command requests;
- displaying server projections, validation and history responses.

React Flow must not own:

- canonical nodes, edges or properties;
- semantic validation or operation ordering;
- durable undo/redo stacks;
- Git, Hibernate or database implementation details;
- an alternative browser document that can diverge from the server.

The accepted update flow is:

```text
user gesture
    -> revision-aware semantic command
    -> server validation and durable append
    -> accepted WorkflowProjection / conflict response
    -> React Flow state replacement
```

Yjs remains optional for non-semantic awareness or UI helpers. It is not a mandatory dependency of the production frontend and may never contain canonical workflow state.

## Collaboration modes

### `PRIVATE_WORKSPACE`

Only the actor sees current edits. Undo/redo is personal. Publishing or merging into a shared workflow is explicit.

### `SHARED_SESSION_PERSONAL_UNDO`

Participants see one live workflow, while each actor may undo only their own eligible operations. This is the recommended shared default.

### `SHARED_SESSION_SHARED_UNDO`

Participants use a room-level undo target. The UI must preview the affected operation and actor before confirmation.

A session mode is immutable for the session lifetime. Undo and redo append new semantic operations; accepted history is never rewritten.

## Versioning and search

Git stores stable workflow checkpoints and supports branch, compare, merge, cherry-pick, revert and restoration semantics. It is not the live-collaboration transport.

```text
current accepted revision
    -> explicit checkpoint command
    -> deterministic DSL tree
    -> JGit commit/ref update
    -> rebuildable search projections
```

Generic Git-history indexing remains in `jgit-storage-hibernate-search`. Audio Analyzer adds only workflow-specific projections and UI adapters.

## Persistence and event boundary

One Hibernate transaction records an accepted live command:

1. semantic operation;
2. canonical DSL snapshot;
3. revision and event sequence;
4. session recovery state;
5. outbox event.

After commit, the leased dispatcher publishes events. Pending, failed or leased events are not removed by retention. Git checkpoints remain separate expected-revision commands; cross-component atomicity is not claimed without a verified shared contract.

## Execution boundary

A workflow run uses an immutable input selected from either a current accepted revision or an exact stored commit:

```text
workflow revision or commit
    -> validation
    -> ExecutionSnapshot + content fingerprint
    -> WorkflowRunService
    -> replaceable WorkflowExecutionBackend
    -> progress, cancellation and typed result artifacts
```

Editing may continue while a run executes. The running job remains tied to its original fingerprint.

## Conflict examples

### Independent properties

```text
User A changes FFT.windowSize
User B changes Classifier.threshold
```

Expected: no semantic conflict.

### Same property changed differently

```text
Base: FFT.windowSize = 2048
User A: FFT.windowSize = 4096
User B: FFT.windowSize = 1024
```

Expected: explicit property conflict, not a raw textual DSL conflict.

### Delete versus connection

```text
User A deletes Node X
User B connects Node X to Node Y
```

Expected: typed invalid-operation or merge conflict response.

## Verification strategy

Domain and application tests cover:

- structural and typed-port validation;
- operation replay and conflicts;
- deterministic DSL roundtrips;
- restart recovery and event ordering;
- migration and retention invariants;
- semantic diff/merge and immutable execution.

Frontend verification covers:

- strict TypeScript checking;
- projection and command adapter unit tests;
- architecture lint rejecting persistence knowledge and canonical Yjs state;
- byte-identical clean production builds;
- cache-safe assets packaged in the application classpath;
- controlled SPA forwarding without API/asset capture.

End-to-end verification opens isolated browser contexts against the packaged Spring Boot process and proves convergence, reconnect/replay, restart and later undo/execution flows.

## Delivery order

Completed:

1. workflow domain, semantic operations and deterministic DSL;
2. `VersionedWorkflowStore` and released Hibernate-backed JGit integration;
3. collaboration sessions, ordered SSE, durable append, restart recovery and leased outbox;
4. production migrations and safe published-outbox retention;
5. editor-stack comparison and ADR-007 selection.

Active and next:

1. **#269** — package the maintained React Flow frontend reproducibly;
2. **#270** — connect sessions, SSE replay/reconciliation and server-owned presence;
3. **#271/#272** — durable semantic undo/redo and React Flow conflict UX;
4. **#246/#247** — semantic diff/merge and rebuildable workflow-history search;
5. **#273/#274/#275** — immutable run orchestration, real DSP execution and run UI;
6. **#249** — staged packaged two-browser end-to-end evidence.

## Related documents

- [`README.md`](README.md) — architecture document map.
- [`bounded-contexts.md`](bounded-contexts.md) — package and dependency boundaries.
- [`adr-006-versioned-collaborative-workflow-store.md`](adr-006-versioned-collaborative-workflow-store.md) — versioned store decision.
- [`adr-007-editor-stack.md`](adr-007-editor-stack.md) — production React Flow adapter decision.
- [`session-event-streaming.md`](session-event-streaming.md) — ordered SSE and replay contract.
- [`../../audio-web-editor/README.md`](../../audio-web-editor/README.md) — maintained frontend build and packaging.

