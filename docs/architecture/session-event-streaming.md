# Collaboration session event streaming

Issue: #242  
Parent epic: #239  
Depends on: #241

## Decision

The first production collaboration transport is Spring MVC Server-Sent Events (SSE). Commands continue to use REST. The browser never publishes directly into an SSE stream and the stream never becomes the source of truth.

```text
REST session command
    -> WorkflowSessionRegistry
    -> canonical WorkflowOperationLog
    -> accepted WorkflowSessionEvent
    -> bounded WorkflowSessionEventHub
    -> Spring MVC SseEmitter
    -> connected browsers
```

The hub lives in `audio-core` and has no Spring, Servlet, JSON, editor-rendering or persistence dependency. It carries immutable `Workflow` snapshots. The HTTP adapter in `audio-app` maps those snapshots to `WorkflowProjection` only at the transport boundary.

## Event contract

Every event contains:

- a stable event id;
- session id;
- monotonically increasing per-session sequence;
- monotonically increasing semantic revision;
- server timestamp;
- event type;
- actor metadata when applicable;
- accepted operation id when applicable;
- a canonical workflow snapshot for semantic/snapshot events;
- immutable operation or presence attributes.

Event types are:

```text
SESSION_CREATED
SESSION_CLOSED
OPERATION_ACCEPTED
PRESENCE_JOINED
PRESENCE_UPDATED
PRESENCE_LEFT
SNAPSHOT
```

Sequence advances for every event. Revision advances only for `OPERATION_ACCEPTED`. Presence therefore cannot change semantic workflow history or checkpoint state.

## HTTP API

```text
POST /workflow/sessions/{sessionId}/operations
PUT  /workflow/sessions/{sessionId}/presence
GET  /workflow/sessions/{sessionId}/events
```

The SSE endpoint accepts either:

```text
Last-Event-ID: 42
```

or:

```text
?afterSequence=42
```

The query parameter wins when both are present. SSE `id` is the numeric sequence and SSE `event` is the event type.

## Replay and reconnect

Each session retains a bounded event window. A reconnect cursor still inside that window receives all later events in order. A cursor older than the retained window, or ahead of the server sequence, receives one current `SNAPSHOT` event instead of a misleading partial replay.

The snapshot uses the current sequence and semantic revision. After applying it, a client resumes from that sequence.

## Duplicate suppression

A semantic operation id is unique within a session.

- Retrying the same operation id with the same semantic type, author, affected object ids and payload is idempotent. It returns the current workflow without appending or broadcasting another event.
- Reusing the same id for different semantic content returns `DUPLICATE_OPERATION_ID` and HTTP `409 Conflict`.
- Rejected operations never produce `OPERATION_ACCEPTED`.

## Slow and failed transports

Every subscriber has a bounded queue and a dedicated Java 21 virtual dispatch thread. Publication to one session does not call `SseEmitter` on the semantic command thread.

A subscriber is removed when:

- its callback throws;
- its queue overflows because it cannot keep up;
- the emitter completes, times out or reports an error;
- the client closes the transport;
- the session emits `SESSION_CLOSED`.

Removing a transport subscription never removes the collaboration session or its workflow. Session lifecycle remains owned by `WorkflowSessionRegistry`.

## Durability boundary

This implementation is deliberately in-memory and bounded. Durable operation persistence, transactional outbox delivery and recovery across process restarts belong to #245. A future broker or WebSocket adapter must consume the same transport-neutral event contract instead of bypassing the registry.
