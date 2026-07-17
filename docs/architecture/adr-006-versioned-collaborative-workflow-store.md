# ADR-006: Versioned collaborative workflow store

Status: Accepted with spike gates  
Date: 2026-07-05  
Persistence baseline updated: 2026-07-17

## Context

Audio Analyzer already contains a stable workflow domain in `audio-core`. The current architecture separates design-time workflow objects from execution state and records edits as semantic workflow operations. That is the right foundation for a future graphical workflow editor with replay, undo and collaborative editing.

Taxonomy, Sandbox and the JGit fork also show that DB-backed Git storage through JGit and Hibernate is a useful platform capability. It should not be copied again into Audio Analyzer as project-local infrastructure.

The desired future capability is a graphical workflow editor where users can choose whether they work privately or live with other users on the same drawing. In live sessions, undo may be personal or explicitly shared.

## Decision

Audio Analyzer will use the existing workflow model as the audio-domain basis and add a versioned, collaborative workflow-store layer around it.

The Hibernate-backed JGit store will be consolidated as a separate reusable infrastructure module or repository, named `jgit-storage-hibernate`. Audio Analyzer will use that store through a narrow persistence facade and must not depend directly on JGit internals such as `org.eclipse.jgit.internal.*`.

The production collaboration store uses Hibernate ORM and Jakarta Persistence, not handwritten JDBC repositories. The accepted compatibility baseline is:

```text
Hibernate ORM    7.4.5.Final
Hibernate Search 8.4.0.Final
Jakarta Persistence 3.2
Java             21
```

Hibernate ORM and Hibernate Search move as one compatibility unit. Search projections use the same ORM persistence context as the durable session, operation and outbox entities. A search index is rebuildable derived state and never the source of truth.

The default strategy is **not** to fork JGit for Audio Analyzer. A JGit fork is only acceptable if a dedicated spike proves that upstream JGit cannot support the required transactional semantics through the available DFS/Reftable extension points.

## Architectural shape

```text
Web graph editor / desktop bridge
    -> workflow application service
    -> workflow operation log
    -> audio workflow domain model
    -> deterministic workflow DSL
    -> Hibernate ORM session / operation / outbox transaction
    -> versioned workflow persistence facade
    -> jgit-storage-hibernate
    -> database-backed JGit objects/refs/reflog
    -> Hibernate Search projections
    -> outbox dispatcher
    -> event broker / SSE/WebSocket clients
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

## Persistence decision

The durable collaboration transaction contains one atomic unit of work:

```text
load session aggregate with optimistic version
validate and append WorkflowOperation
update deterministic workflow snapshot and semantic revision
append ordered outbox event
commit Hibernate transaction
```

The persistence adapter uses mapped entities, database constraints and `@Version` optimistic locking. Production schema evolution uses Flyway or Liquibase. Hibernate schema generation is limited to disposable development and integration-test databases.

The following are not accepted as the production persistence implementation:

- `JdbcTemplate`, `JdbcClient` or direct `Connection` repositories;
- handwritten SQL CRUD and row mappers for session/operation/outbox state;
- a second independent Hibernate persistence unit solely for search;
- treating Lucene, Elasticsearch or a broker as authoritative storage.

## Web editor decision

The collaborative graphical editor should be developed web-first, but the existing Swing application should not be rewritten in one step. A web editor can later be hosted as a browser UI, a desktop WebView, or a dedicated module.

React Flow plus Yjs is the accepted first editor stack. React Flow state remains derived from server projections, while Yjs is restricted to awareness and non-semantic UI state. Neither client technology owns canonical workflow semantics or durable history.

## Event transport decision

The event broker is transport, not source of truth. ActiveMQ Artemis or another broker may be used behind an abstraction, but persistence follows a transactional outbox pattern:

```text
Hibernate transaction:
  append workflow operation entity
  update session snapshot/revision
  create optional Git checkpoint
  insert outbox entity
commit

outbox dispatcher:
  publish committed event to SSE/WebSocket/broker clients
  mark delivery attempt/result idempotently
```

## Consequences

Positive:

- avoids a third copy of the JGit/Hibernate storage idea;
- replaces brittle JDBC repositories with a mapped aggregate and optimistic locking;
- aligns full-text projections with the same ORM lifecycle;
- keeps Audio Analyzer focused on audio workflow semantics;
- preserves the existing workflow operation model;
- prepares multi-user editing without forcing an immediate Swing rewrite;
- lets Taxonomy, Sandbox and Audio Analyzer converge on one storage component.

Costs and risks:

- ORM 7.4 and Search 8.4 must be upgraded and tested together;
- the storage spike becomes a required prerequisite;
- JGit internal API usage must be isolated and version-pinned;
- collaboration requires a persisted operation log, outbox and conflict policy;
- shared undo must be opt-in because it can revert another user's work;
- Lucene/Search schema changes can require a full reindex.

## Spike gates

This ADR is accepted only with the following gates:

1. **JGit storage gate**: prove whether a separate Hibernate-backed storage module can compile and run against a regular JGit release without core patches.
2. **Transactional gate**: prove that operation log entries, current session state and outbox events are atomically consistent through one Hibernate transaction, with Git checkpoint consistency defined explicitly.
3. **Workflow gate**: prove a minimal `Input -> Gain -> Output` workflow roundtrip through domain model, DSL, DB-backed Git commit, Search projection and reload.
4. **Collaboration gate**: prove private mode and a shared-session undo mode with at least two simulated clients.
5. **Recovery gate**: prove optimistic conflict handling, process restart recovery and publication retry without data loss.

## Follow-up

The shared `jgit-storage-hibernate` module has been upgraded to Hibernate ORM 7.4.5.Final and Hibernate Search 8.4.0.Final. Audio Analyzer issue #245 implements the mapped collaboration/outbox store; issue #247 implements rebuildable Search projections. The raw-JDBC implementation path is superseded.
