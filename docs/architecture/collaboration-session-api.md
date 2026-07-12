# Collaboration Session Lifecycle API

Issue: #241  
Parent epic: #239

## Purpose

This API exposes collaboration-session lifecycle and actor membership without coupling the domain to authentication, React Flow, Yjs, JGit or persistence internals.

The canonical workflow of each session is held by one `WorkflowOperationLog`. Browser transports and future authentication adapters provide `OperationActor` metadata but do not define its semantics.

## Lifecycle rules

- Session identifiers are stable and unique until the session is explicitly closed.
- The creating actor is the session owner and is joined immediately.
- `PRIVATE_WORKSPACE` may only be joined by its owner.
- Shared sessions accept multiple actors.
- Repeating a join with identical actor metadata is idempotent.
- Reusing an actor identifier with different metadata is rejected.
- Leaving removes active membership and presence but retains workflow state for reconnect.
- Empty sessions are retained until the owner explicitly closes them.
- Only the owner may close a session.
- Collaboration mode is immutable for the lifetime of a session.
- Semantic operations require a joined actor, matching session mode and an operation author equal to the actor identifier.

Durable cleanup and expiration policies belong to #245. The in-memory registry intentionally performs no time-based deletion.

## HTTP endpoints

```text
POST   /workflow/sessions
GET    /workflow/sessions
POST   /workflow/sessions/{sessionId}/join
POST   /workflow/sessions/{sessionId}/leave
GET    /workflow/sessions/{sessionId}
GET    /workflow/sessions/{sessionId}/projection
DELETE /workflow/sessions/{sessionId}
```

### Create

```json
{
  "sessionId": "experiment-42",
  "mode": "SHARED_SESSION_PERSONAL_UNDO",
  "actor": {
    "actorId": "actor-alice",
    "userId": "user-alice",
    "displayName": "Alice"
  },
  "workflowId": "workflow-42",
  "workflowName": "Wingbeat experiment"
}
```

`workflowId` and `workflowName` are optional. The initial session graph is empty; loading a stored workflow is integrated by the persistence work tracked in #240.

### Join

```json
{
  "actorId": "actor-bob",
  "userId": "user-bob",
  "displayName": "Bob"
}
```

### Leave or close

```json
{
  "actorId": "actor-bob"
}
```

For `DELETE`, the actor must be the session owner.

## Error mapping

| Condition | HTTP status |
|---|---:|
| Invalid or incomplete request | 400 |
| Unknown session | 404 |
| Duplicate session, private-session join, invalid lifecycle transition | 409 |
| Wrong HTTP method | 405 |

## Running standalone

The API has a dedicated headless launcher:

```text
org.hammer.audio.workflow.editor.http.WorkflowSessionHttpServerLauncher [port]
```

The default port is `8081`. Integration into the combined production workbench launcher can happen with #240/#242 when persistent session wiring and event streaming are introduced.

## Architectural boundary

`WorkflowSessionRegistry` is an application-layer service in `audio-core`. It depends only on workflow and collaboration-domain types. `WorkflowSessionHttpAdapter` is a replaceable transport adapter in `audio-app`.
