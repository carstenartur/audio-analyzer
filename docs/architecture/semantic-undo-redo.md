# Durable semantic undo and redo

Status: Implemented by issue #271  
Depends on: durable session append #261, restart recovery #263, ordered events #242

## Decision

Undo and redo are server-side semantic commands. They never remove an earlier operation, rewrite Git history or mutate an accepted outbox event.

```text
client command
    -> actor/session/mode/revision validation
    -> durable target selection and conflict analysis
    -> fresh semantic inverse operation
    -> normal operation validation
    -> one atomic session append
         operation body + command relation
         canonical workflow DSL
         semantic revision + event sequence
         transactional outbox event
    -> ordered SSE projection
```

The collaboration path does not call `WorkflowOperationLog.undoLast()`. That legacy helper removes the last in-memory operation and therefore cannot provide audit-preserving multi-user history.

## Command identities

Every history command has a stable client-supplied `commandId`. An accepted command creates a fresh semantic operation id derived from it:

```text
<commandId>:operation
```

Durable metadata records:

- `NORMAL`, `UNDO` or `REDO`;
- the stable command id;
- the targeted operation for undo, or targeted undo operation for redo.

The inverse returned by `WorkflowOperation.inverseOperation()` is treated only as a semantic template. Its fixed `:undo` metadata is replaced before append, so repeated undo/redo cycles cannot collide.

## Reconstructible operation bodies

The pre-existing `operation_payload` remains the compact deterministic idempotency fingerprint. It is not sufficient for undo after restart: for example, the identity of a deleted node does not contain its complete node, port, metadata and edge snapshots.

New operations therefore also store a versioned complete operation body:

```text
operation_body_version = 1
operation_body         = URL-safe Base64 of deterministic length-prefixed binary data
```

The framework-independent codec in `audio-core` covers every current `WorkflowOperation` subtype and all nested domain values. It does not use Java object serialization, Jackson, Hibernate or database classes.

Migration V3 adds nullable body and command-relation columns. Existing rows remain readable and retain their retry fingerprint. Because they cannot be reconstructed safely, attempts to undo such a row return `OPERATION_NOT_UNDOABLE` rather than inventing missing state.

## Personal undo

`PRIVATE_WORKSPACE` and `SHARED_SESSION_PERSONAL_UNDO` select the requesting actor's latest active undoable operation. A later operation from another actor that intersects any affected semantic object blocks the command.

The preview and conflict response identify:

- the target operation and original actor;
- affected object ids;
- each blocking operation and actor;
- the precise intersecting object ids.

A rejected command appends no operation, advances no revision/sequence and creates no outbox residue.

## Shared undo

`SHARED_SESSION_SHARED_UNDO` never performs an implicit cross-user “undo last”. The caller must first request an immutable preview for an explicit target operation and then confirm with the returned `previewId`.

The preview identity is bound to:

- session id;
- current semantic revision;
- target operation;
- current blocker set.

Any intervening semantic operation makes the old preview stale. The server returns `UNDO_PREVIEW_STALE` and requires a new preview.

## Redo

Redo targets one accepted undo operation owned by the requesting actor. It creates a new operation that semantically inverts that undo. A redo is rejected when:

- the target is missing or not an undo;
- the requesting actor does not own the undo;
- the undo has already been redone;
- later semantic state conflicts with the inverse;
- the expected revision is stale.

Redo is therefore a new audited command, not removal of the undo record.

## REST API

```text
POST /workflow/sessions/{id}/undo/preview
POST /workflow/sessions/{id}/undo
POST /workflow/sessions/{id}/redo
```

Accepted undo/redo responses contain the canonical workflow projection, command kind/id/target, fresh operation id, resulting revision and event sequence. Rejections use RFC 9457 problem details with stable codes. Semantic conflicts additionally expose target and blocker details.

## Ordered events

Accepted history commands continue to use `OPERATION_ACCEPTED`. SSE attributes distinguish them without introducing a parallel event hierarchy:

```text
operationType
operationAuthor
commandKind
commandId
targetOperationId   # undo/redo only
```

Clients rebuild graph state only from the accepted projection. Presence remains separate and never participates in undo history.

## Restart and concurrency guarantees

Open sessions recover the ordered operation body and command relation from Hibernate. Tests prove:

- an undo accepted before complete process shutdown is restored;
- retrying the same command after restart is idempotent;
- redo can be accepted after restart;
- a second restart restores the redone canonical workflow;
- two different commands at the same expected revision cannot both append.

The session lock serializes in-process commands. The durable store independently revalidates revision under pessimistic locking, so the same invariant holds across competing application instances.

## Verification boundaries

- Core round-trip tests cover every semantic operation subtype and malformed/truncated bodies.
- Core command tests cover personal selection, shared preview, stale preview, remote conflict, idempotent retry, redo and concurrent commands.
- HTTP tests cover accepted command responses and machine-readable conflict problems.
- Hibernate tests cover operation-body round trips, migration compatibility and complete restart recovery.
- PostgreSQL Testcontainers migration coverage validates the same V3 schema used in production.
