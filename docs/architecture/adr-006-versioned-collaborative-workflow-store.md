# ADR-006: Versioned collaborative workflow store

Status: Accepted with spike gates  
Date: 2026-07-05

## Context

Audio Analyzer already contains a stable workflow domain in `audio-core`. The current architecture separates design-time workflow objects from execution state and records edits as semantic workflow operations. That is the right foundation for a future graphical workflow editor with replay, undo and collaborative editing.

Taxonomy, Sandbox and the JGit fork also show that DB-backed Git storage through JGit and Hibernate is a useful platform capability. It should not be copied again into Audio Analyzer as project-local infrastructure.

The desired future capability is a graphical workflow editor where users can choose whether they work privately or live with other users on the same drawing. In live sessions, undo may be personal or explicitly shared.

## Decision

Audio Analyzer will use the existing workflow model as the audio-domain basis and add a versioned, collaborative workflow-store layer around it.

The Hibernate-backed JGit store will be consolidated as a separate reusable infrastructure module or repository, tentatively named `jgit-storage-hibernate` or `hibernate-jgit-store`. Audio Analyzer will use that store through a narrow persistence facade and must not depend directly on JGit internals such as `org.eclipse.jgit.internal.*`.

The default strategy is **not** to fork JGit for Audio Analyzer. A JGit fork is only acceptable if a dedicated spike proves that upstream JGit cannot support the required transactional semantics through the available DFS/Reftable extension points.

## Architectural shape

```text
Web graph editor / desktop bridge
    -> workflow application service
    -> workflow operation log
    -> audio workflow domain model
    -> deterministic workflow DSL
    -> versioned workflow persistence facade
    -> jgit-storage-hibernate
    -> database-backed JGit objects/refs/reflog
    -> Hibernate Search projections
    -> transactional outbox
    -> event broker / WebSocket clients
```

## Collaboration modes

The editor must support explicit collaboration modes:

```text
PRIVATE_WORKSPACE
    Only the actor sees their current changes.
    Undo/redo is personal.
    Changes are published or merged explicitly.

SHARED_SESSION_PERSONAL_UNDO
    Participants see the same live drawing.
    Each participant's undo stack contains only their own undoable operations.
    This is the recommended default for live collaboration.

SHARED_SESSION_SHARED_UNDO
    Participants share one room-level undo stack.
    Undo can revert another user's operation and therefore must be explicit in the UI.
```

Undo and redo are represented as semantic operations. Git commits are durable history checkpoints, not the local editor undo stack.

## Web editor decision

The collaborative graphical editor should be developed web-first, but the existing Swing application should not be rewritten in one step. A web editor can later be hosted as a browser UI, a desktop WebView, or a dedicated module.

GLSP is the preferred architecture spike for the serious model-driven editor path because the server can remain authoritative over the semantic source model. A simpler React Flow prototype remains useful for UX validation, but it must not become the canonical workflow state by accident.

## Event transport decision

The event broker is transport, not source of truth. ActiveMQ Artemis or another broker may be used behind an abstraction, but persistence must follow a transactional outbox pattern:

```text
DB transaction:
  append workflow operation
  update model/checkpoint/ref/projections
  insert outbox event
commit

outbox dispatcher:
  publish committed event to broker/WebSocket clients
```

## Consequences

Positive:

- avoids a third copy of the JGit/Hibernate storage idea;
- keeps Audio Analyzer focused on audio workflow semantics;
- preserves the existing workflow operation model;
- prepares multi-user editing without forcing an immediate Swing rewrite;
- lets Taxonomy, Sandbox and Audio Analyzer converge on one storage component.

Costs and risks:

- the storage spike becomes a required prerequisite;
- JGit internal API usage must be isolated and version-pinned;
- collaboration requires a persisted operation log, outbox and conflict policy;
- shared undo must be opt-in because it can revert another user's work.

## Spike gates

This ADR is accepted only with the following gates:

1. **JGit storage gate**: prove whether a separate Hibernate-backed storage module can compile and run against a regular JGit release without core patches.
2. **Transactional gate**: prove that blob/tree/commit writes, ref updates, operation log entries and outbox events can be made atomically consistent.
3. **Workflow gate**: prove a minimal `Input -> Gain -> Output` workflow roundtrip through domain model, DSL, DB-backed Git commit, search projection and reload.
4. **Collaboration gate**: prove private mode and a shared-session undo mode with at least two simulated clients.

## Follow-up

The next implementation item is the JGit/Hibernate storage spike, documented in `docs/architecture/jgit-storage-hibernate-spike.md`.
