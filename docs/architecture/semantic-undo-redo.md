# Durable semantic undo and redo

Status: Implemented by issues #271 and #284  
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

`PRIVATE_WORKSPACE` and `SHARED_SESSION_PERSONAL_UNDO` select the requesting actor's latest active operation. The server does not silently skip that operation when it is blocked or predates reconstructible operation bodies.

Any later accepted operation that intersects an affected semantic object blocks the command, including a later operation from the same actor. The preview and conflict response identify:

- the target operation, original actor and timestamp;
- affected object ids;
- each blocking operation and actor;
- the precise intersecting object ids.

A rejected command appends no operation, advances no revision or sequence and creates no outbox residue.

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

Redo is therefore a new audited command, not removal of the undo record. A read-only redo preview uses the same target validation and blocker analysis as execution.

## Durable history discovery

The ordered accepted-operation sequence recovered into each session is also the only source for history discovery. The public descriptor contains:

- operation id, semantic type, actor and occurrence timestamp;
- semantic revision and durable event sequence;
- command kind, command id and target relation;
- affected semantic object ids when a reconstructible body is available;
- reconstructibility and current active undo/redo-target flags.

History pages are ordered by descending semantic revision. `beforeRevision` is an exclusive cursor, so inserting newer operations cannot reorder or duplicate entries on older pages. The server caps a page at 100 entries.

Actor-scoped capabilities report:

- the immutable collaboration mode and current semantic revision;
- whether personal undo is permitted;
- the actor's current personal-undo target without skipping a blocked or legacy target;
- the actor's current redo target;
- `AVAILABLE`, `BLOCKED` or `NOT_RECONSTRUCTIBLE` status;
- whether explicit shared-target undo is permitted.

History, capability and preview queries run under the same per-session lock and reuse the same conflict analysis as command execution. They do not append an operation, advance revision or event sequence, publish SSE, or write an outbox entry.

## REST API

```text
POST /workflow/sessions/{id}/history/query
POST /workflow/sessions/{id}/history/capabilities
POST /workflow/sessions/{id}/undo/preview
POST /workflow/sessions/{id}/undo
POST /workflow/sessions/{id}/redo/preview
POST /workflow/sessions/{id}/redo
```

POST query bodies carry the complete actor identity because membership and actor metadata are validated at the application boundary. History requests may additionally carry `beforeRevision` and `limit`.

Accepted undo/redo responses contain the canonical workflow projection, command kind, command id, target, fresh operation id, resulting revision and event sequence. Rejections use RFC 9457 problem details with stable codes. Semantic conflicts additionally expose target and blocker details.

## Ordered events versus history queries

SSE and durable history answer different questions:

|       Mechanism       |                             Responsibility                              | Authoritative for semantic eligibility? |
|-----------------------|-------------------------------------------------------------------------|-----------------------------------------|
| Ordered SSE replay    | Deliver accepted changes and live presence after a cursor               | No                                      |
| Canonical snapshot    | Recover graph state after an event gap or restart                        | No                                      |
| Durable history query | Browse immutable accepted operations                                    | No, it is descriptive                   |
| Capability query      | Discover the actor's current server-selected undo/redo targets           | Yes at the returned revision            |
| Undo/redo preview     | Explain one target and current blockers immediately before confirmation | Yes at the returned revision            |
| Undo/redo command     | Revalidate expected revision and atomically append an inverse            | Final authority                         |

A browser must not infer a complete undo stack from the SSE window. It must reload capabilities after join, full reload, reconciliation and every accepted history command. A cached capability or preview never overrides expected-revision validation during execution.

Accepted history commands continue to use `OPERATION_ACCEPTED`. SSE attributes distinguish them without introducing a parallel event hierarchy:

```text
operationType
operationAuthor
commandKind
commandId
targetOperationId   # undo/redo only
```

Clients rebuild graph state only from the accepted projection. Presence remains separate and never participates in undo history. Browser-local or Yjs undo may cover non-semantic viewport helpers, but it never restores canonical nodes, edges or properties.

## Restart and concurrency guarantees

Open sessions recover operation bodies, timestamps, revisions, event sequences and command relations from Hibernate. Tests prove:

- an undo accepted before complete process shutdown is restored;
- retrying the same command after restart is idempotent;
- redo can be accepted after restart;
- history order and command-target linkage remain identical after restart;
- actor-scoped redo discovery and redo preview work after restart;
- read-only queries leave operation count, revision and durable rows unchanged;
- a second restart restores the redone canonical workflow;
- two different commands at the same expected revision cannot both append.

The session lock serializes in-process commands and read-only capability calculations. The durable store independently revalidates revision under pessimistic locking, so the same mutation invariant holds across competing application instances.

## Verification boundaries

- Core round-trip tests cover every semantic operation subtype and malformed or truncated bodies.
- Core command tests cover personal selection, shared preview, stale preview, same-actor and remote conflict, idempotent retry, redo and concurrent commands.
- Core history tests cover newest-first pagination, stable cursors, capability transitions, blockers and immutable mode behavior.
- HTTP tests cover history and capability contracts, timestamp-aware previews, validation and accepted command responses.
- Hibernate tests cover operation-body round trips, migration compatibility, complete restart recovery and read-only history stability.
- PostgreSQL Testcontainers migration coverage validates the same V3 schema used in production.

