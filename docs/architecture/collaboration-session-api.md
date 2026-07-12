# Collaboration Session Lifecycle API

Issue: #241  
Parent epic: #239

## Purpose

This API exposes collaboration-session lifecycle and actor membership without coupling the domain to authentication, React Flow, Yjs, JGit or persistence internals.

The canonical workflow of each session is held by one `WorkflowOperationLog`. Browser transports and future authentication adapters provide `OperationActor` metadata but do not define its semantics.

## Runtime technology

The API is part of the existing Spring Boot workbench application and uses:

- Spring Boot 4.1.0;
- Spring MVC controllers;
- Jakarta Bean Validation for request contracts;
- Jackson 3 for JSON binding;
- RFC 9457 `ProblemDetail` responses;
- Spring MockMvc for controller integration tests.

The framework boundary remains in `audio-app`. `audio-core` contains no Spring, Servlet, JSON or HTTP dependencies.

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

A successful creation returns `201 Created`, a `Location` header and the created session representation.

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

For `DELETE`, the actor must be the session owner. A successful close returns `204 No Content`.

## Error responses

Errors use `application/problem+json`. Domain failures expose stable error codes rather than requiring clients to parse exception messages.

```json
{
  "type": "https://audio-analyzer.dev/problems/session-not-found",
  "title": "Session not found",
  "status": 404,
  "detail": "Unknown session: experiment-42",
  "instance": "/workflow/sessions/experiment-42",
  "code": "SESSION_NOT_FOUND",
  "sessionId": "experiment-42"
}
```

| Condition | HTTP status |
|---|---:|
| Invalid JSON or failed Bean Validation | 400 |
| Invalid operation author | 400 |
| Unknown session | 404 |
| Duplicate session, private-session access or invalid lifecycle transition | 409 |
| Wrong HTTP method | 405 |

## Running

The API starts together with the workflow workbench:

```text
java -jar audio-app-0.0.4-SNAPSHOT-workbench.jar
```

The default port is `8080` and can be changed with `--server.port=<port>`.

## Architectural boundary

`WorkflowSessionRegistry`, `WorkflowSessionException` and the collaboration model are application/domain services in `audio-core`. They depend only on workflow and collaboration types. `WorkflowSessionHttpAdapter`, request/response DTOs and `WorkflowApiExceptionHandler` are replaceable Spring MVC transport adapters in `audio-app`.
