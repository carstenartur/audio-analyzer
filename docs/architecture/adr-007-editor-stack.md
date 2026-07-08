# ADR-007: Editor Stack Selection for Experiment Modeling Workbench

**Issue**: [#221](https://github.com/carstenartur/audio-analyzer/issues/221)  
**Status**: Proposed — pending spike evidence  
**Date**: 2026-07-07  
**Related spikes**: [#219 GLSP](https://github.com/carstenartur/audio-analyzer/issues/219), [#220 React Flow/Yjs](https://github.com/carstenartur/audio-analyzer/issues/220)

---

## Context

The Experiment Modeling Workbench (issue #206) needs a graph editor. Two candidates must be evaluated with comparable spikes:

1. **GLSP** (Graphical Language Server Protocol) — Eclipse/EclipseSource model-driven editor
2. **React Flow + Yjs** — lightweight React graph renderer with optional CRDT collaboration helpers

The canonical workflow state must always remain in:

```text
audio-core Workflow
  -> WorkflowOperationLog
  -> VersionedWorkflowStore
```

The editor is an adapter layer only. It must translate user gestures into semantic `WorkflowOperation` values; it must not become the source of truth.

This ADR is intentionally **not accepted yet**. It records the selection criteria, current working hypothesis and guardrails. It becomes accepted only after #219 and #220 have produced comparable evidence.

---

## Evaluation criteria

|              Criterion              | Weight |
|-------------------------------------|--------|
| Server-authoritative workflow model | High   |
| Typed ports and semantic validation | High   |
| Personal undo feasibility           | High   |
| Deterministic replay/audit          | High   |
| Adapter complexity / cognitive load | High   |
| Future collaboration readiness      | Medium |
| Maintainability                     | Medium |

---

## Option A: GLSP

### What GLSP owns

- JSON-based diagram model (GModel) rendered in a VS Code / Eclipse Theia / browser panel
- Node, edge and port rendering
- Layout computation helpers
- Client-server JSON-RPC protocol

### What GLSP must not own

- Canonical workflow state — GModel is a derived view of `audio-core Workflow`
- Validation logic — `WorkflowValidator` owns type-compatibility checks
- Persistence — `VersionedWorkflowStore` owns durable checkpoints

### Integration pattern

```text
User gesture (GLSP client)
  -> CreateNodeOperation / CreateEdgeOperation (GLSP action)
  -> WorkflowOperationHandler (Audio Analyzer server)
      translates GModel action -> WorkflowOperation
      applies WorkflowOperation to WorkflowOperationLog
      updates GModel projection from new Workflow state
  -> GModel delta -> GLSP client
```

### Strengths

- Purpose-built model-driven editor with typed diagrams
- Rich port and connector model natively
- Server-authoritative by design when the GLSP server is wired to the domain model
- Eclipse ecosystem, mature tooling

### Weaknesses

- High setup complexity (Theia or VS Code extension host likely required)
- Steep learning curve for developers unfamiliar with the GLSP ecosystem
- Two-model overhead: GModel + audio-core Workflow require explicit synchronization
- Large framework footprint for the first research-workbench MVP

---

## Option B: React Flow + Yjs

### What React Flow owns

- Node and edge rendering in the browser
- Layout drag-and-drop (client-side only)
- Viewport and zoom state

### What Yjs may own (optional, scoped)

- Awareness state (user cursors, presence)
- Optimistic client-side layout helpers
- Local collaboration helpers that are later reconciled through semantic operations

### What React Flow/Yjs must not own

- Canonical workflow state — always held server-side in `audio-core Workflow`
- Semantic validation — stays in `WorkflowValidator`
- Durable history — stays in `VersionedWorkflowStore`
- Conflict resolution for semantic operations — CRDT merges must never hide semantic conflicts

### Integration pattern

```text
User gesture (React Flow UI)
  -> HTTP/WebSocket call to Audio Analyzer application service
      validates WorkflowOperation (WorkflowValidator)
      applies WorkflowOperation to WorkflowOperationLog
      optionally commits to VersionedWorkflowStore
      returns updated Workflow projection
  -> React Flow node/edge state updated from server response
```

### Strengths

- Low barrier to entry (standard React + npm)
- Flexible rendering: typed ports can be rendered as custom node handles
- Yjs awareness is optional and additive; collaboration can be wired in later
- Fewer framework layers between developer and browser

### Weaknesses

- No built-in model-driven constraint enforcement
- Requires explicit discipline to keep the server authoritative
- Optimistic updates risk state drift if adapters are careless
- Yjs document state must never be used as durable canonical store

---

## Proposed starting hypothesis

React Flow + Yjs is the current recommended starting hypothesis because it appears likely to keep the first MVP's cognitive load lower than GLSP:

1. A React developer can work against one narrow HTTP/WebSocket adapter that sends `WorkflowOperation` requests.
2. The first workbench is a research/productivity workbench, not an enterprise-scale diagramming platform.
3. The boundary between React Flow state and `audio-core Workflow` is simple to test if all mutations go through application services.
4. Yjs awareness can be introduced for cursors/presence without making a Yjs document the canonical workflow.

This is not a final decision. The hypothesis must be rejected if the React Flow/Yjs spike cannot keep server-authoritative operations, deterministic replay and validation clean.

GLSP remains a valid candidate if the GLSP spike shows that its model-driven structure keeps the adapter cleaner or safer despite higher setup cost.

---

## Boundaries that any final decision must enforce

|          Layer           |                      Owns                       |                Must not own                 |
|--------------------------|-------------------------------------------------|---------------------------------------------|
| Graph editor UI          | Node/edge rendering, layout, viewport state     | Canonical workflow, validation, history     |
| Yjs or equivalent helper | Awareness/presence, optional optimistic helpers | Durable state, semantic conflict resolution |
| HTTP/WebSocket adapter   | `WorkflowOperation` translation                 | Persistence internals, DSL format details   |
| Application service      | Validate, apply, checkpoint workflow operations | UI rendering, storage internals             |
| `VersionedWorkflowStore` | Durable checkpoints and history                 | Editor state, collaboration sessions        |
| `audio-core Workflow`    | Semantic graph, operations, execution snapshots | UI, persistence, JGit, React, Yjs           |

---

## Acceptance path

This ADR may be changed to **Accepted** only when:

- issue #219 records the GLSP spike result;
- issue #220 records the React Flow/Yjs spike result;
- both spikes use the same backend/application API;
- the accepted decision explains why the chosen path keeps cognitive load lower;
- the accepted decision lists fallback criteria and migration path.

---

## Migration / fallback

If React Flow proves too difficult to keep server-authoritative, the fallback path is:

1. Extract all semantic operation translation into a clean `WorkflowEditorService` that accepts only `WorkflowOperation` inputs and returns `Workflow` projections.
2. Replace React Flow with GLSP by pointing GLSP action handlers at `WorkflowEditorService`.
3. Keep `audio-core` model, DSL serializer and `VersionedWorkflowStore` unchanged.

If GLSP proves too heavy for the first useful editor, keep React Flow as the UI layer and limit Yjs to awareness/layout helpers until semantic collaboration is implemented through `WorkflowOperation` events.
