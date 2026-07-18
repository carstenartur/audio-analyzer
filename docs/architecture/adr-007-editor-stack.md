# ADR-007: React Flow as the production workflow editor adapter

**Issue:** [#221](https://github.com/carstenartur/audio-analyzer/issues/221)  
**Status:** Accepted  
**Decision date:** 2026-07-08  
**Production packaging:** issue #269

## Context

The Experiment Modeling Workbench needs a browser graph editor while keeping the existing Java workflow model authoritative. Comparable GLSP and React Flow/Yjs spikes evaluated typed ports, semantic validation, collaboration readiness, replay/undo boundaries and maintainability.

The canonical workflow state remains:

```text
audio-core Workflow
  -> WorkflowOperationLog
  -> VersionedWorkflowStore
```

The editor translates user gestures into semantic operations and renders the server projection. It is not a second workflow model.

## Decision

Use **React Flow** as the maintained production rendering/input adapter.

- `audio-web-editor` is the single maintained browser source module.
- React Flow owns rendering, selection, viewport and local layout interaction.
- Semantic changes are submitted to the server and accepted UI state is rebuilt from the returned `WorkflowProjection`.
- `WorkflowValidator` remains the type-compatibility authority.
- `WorkflowOperationLog` remains the semantic replay/undo foundation.
- `VersionedWorkflowStore` remains the checkpoint/history facade.
- Yjs is optional for non-semantic awareness or UI helpers and is not a mandatory production dependency.
- GLSP remains a fallback adapter, not a second production editor maintained in parallel.

## Rationale

### Lower adapter complexity

React Flow needs one browser component and the existing HTTP/SSE application boundary. GLSP would add GModel lifecycle, action translation and a larger protocol/runtime surface while still requiring synchronization with `audio-core Workflow`.

### Server authority remains explicit

The accepted flow is:

```text
React Flow gesture
  -> semantic HTTP command
  -> WorkflowEditorService
  -> validation and WorkflowOperationLog append
  -> returned WorkflowProjection
  -> React Flow state replacement
```

Optimistic browser state must not become durable or hide a rejected semantic operation.

### Appropriate workbench scope

The project needs a modular research workbench rather than a complete IDE platform. A pinned Vite/React build provides the required typed-node UX with a smaller maintenance burden.

## Ownership boundaries

| Layer | Owns | Must not own |
|---|---|---|
| React Flow UI | Node/edge rendering, selection, viewport, local layout | Canonical workflow, validation, durable history |
| Optional Yjs helper | Awareness and non-semantic UI state | Nodes, edges, semantic operations, checkpoints |
| HTTP/SSE adapter | Command/response and event transport | Persistence implementation, UI rendering |
| Application service | Validate, order and apply semantic operations | Browser state, storage internals |
| `VersionedWorkflowStore` | Durable checkpoints and history | Live editor state, presence |
| `audio-core Workflow` | Semantic graph and execution snapshots | React, Yjs, persistence frameworks |

## Production implementation

Issue #269 promotes the spike into `audio-web-editor`:

- Node and npm versions are downloaded and pinned by Maven.
- `npm ci`, strict type checking, frontend tests and architecture lint run from `mvn clean verify`.
- Two independent clean Vite builds must produce identical filenames and bytes.
- Vite emits content-hashed assets below `target/` only.
- Maven packages the assets under `/workbench-ui` in the `audio-web-editor` JAR.
- `audio-app` consumes that JAR and the executable Spring Boot workbench serves the application without a Vite server.
- `workflow-editor-spike/README.md` remains historical evidence; it is not a second executable source tree.

## Evidence

- [GLSP spike notes](glsp-spike-notes.md)
- [React Flow/Yjs spike notes](react-flow-yjs-spike-notes.md)
- `WorkflowEditorServiceTest` for accepted/rejected semantic operations
- `ProductionFrontendPackagingTest` for classpath assets and cache-safe names
- `WorkbenchSpaControllerTest` for client-route forwarding without API capture
- `audio-web-editor/test/` for browser-projection utilities

## Consequences

### Positive

- One clear production frontend location.
- Reproducible clean builds without developer-global Node installations.
- Static assets ship with the normal application artifact.
- Browser code has no storage implementation knowledge.
- Collaboration, undo and execution UI can build on the same adapter without moving domain authority into JavaScript.

### Trade-offs

- Typed semantic constraints must be represented explicitly by server projections and command responses.
- Browser and Java contracts require compatibility tests.
- Rich IDE-style features may require additional React Flow adapters or a future GLSP reassessment.

## Fallback

If React Flow cannot remain server-authoritative, keep `WorkflowEditorService`, the workflow DSL and `VersionedWorkflowStore` unchanged and replace only the browser adapter with GLSP action handlers. No persistence or domain migration is implied by that fallback.
