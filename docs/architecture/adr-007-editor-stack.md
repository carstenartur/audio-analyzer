# ADR-007: Editor Stack Selection for Experiment Modeling Workbench

**Issue**: [#221](https://github.com/carstenartur/audio-analyzer/issues/221)  
**Status**: Accepted  
**Date**: 2026-07-07  
**Related spikes**: [#219 GLSP](https://github.com/carstenartur/audio-analyzer/issues/219), [#220 React Flow/Yjs](https://github.com/carstenartur/audio-analyzer/issues/220)

---

## Context

The Experiment Modeling Workbench (issue #206) needs a graph editor. Two candidates were
evaluated through spike analysis:

1. **GLSP** (Graphical Language Server Protocol) — Eclipse/EclipseSource model-driven editor
2. **React Flow + Yjs** — lightweight React graph renderer with CRDT collaboration helpers

The canonical workflow state must always remain in:

```text
audio-core Workflow
  -> WorkflowOperationLog
  -> VersionedWorkflowStore
```

The editor is an *adapter layer* only. It must translate user gestures into semantic
`WorkflowOperation` values; it must not become the source of truth.

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
- Server-authoritative by design (the GModel server holds canonical state)
- Eclipse ecosystem, mature tooling

### Weaknesses

- High setup complexity (Theia or VS Code extension host required)
- Steep learning curve for developers unfamiliar with the EMF/GLSP ecosystem
- Two-model overhead: GModel + audio-core Workflow require explicit synchronisation
- Large framework footprint for what is currently a research workbench

---

## Option B: React Flow + Yjs

### What React Flow owns

- Node and edge rendering in the browser (React)
- Layout drag-and-drop (client-side only)
- Viewport and zoom state

### What Yjs may own (optional, scoped)

- Awareness state (user cursors, presence)
- Optimistic client-side undo helpers (scoped to layout, not to semantic state)

### What React Flow/Yjs must NOT own

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
- Flexible rendering: typed ports renderable as custom node handles
- Yjs awareness is optional and additive — collaboration can be wired in later
- Fewer layers between developer and browser

### Weaknesses

- No built-in model-driven constraint enforcement
- Requires explicit discipline to keep server authoritative (optimistic updates risk state drift)
- Yjs document state must never be used as durable canonical store (explicit architectural guard)

---

## Decision

**React Flow + Yjs is the recommended starting point** for the following reasons:

1. **Lower cognitive load per layer.** A React developer needs to understand one layer (HTTP/WS
   adapter calling `WorkflowOperation` API) rather than also understanding GModel, JSON-RPC
   handlers, and Theia/VS Code extension infrastructure.

2. **Faster iteration for a research workbench.** The workbench is for reproducible experiment
   configurations, not for large-scale industrial diagram authoring. React Flow's simplicity
   accelerates the first working prototype.

3. **Layer boundary is enforceable.** The boundary between React Flow state (UI layout) and
   `audio-core Workflow` (semantic state) is the HTTP/WS API. Architecture tests in
   `ArchitectureFitnessTest` already prevent server-side code from depending on UI/React packages.

4. **Collaboration can be added incrementally.** Yjs awareness can be wired in for cursor/presence
   without ever storing Yjs document state as canonical workflow state.

GLSP remains a valid future choice if the workbench grows to enterprise scale and the team needs
a model-driven EMF-grade editor. The decision should be revisited if React Flow proves unable to
enforce server-authoritative semantics with acceptable complexity.

---

## Boundaries enforced by this decision

|          Layer           |                        Owns                        |                Must not own                 |
|--------------------------|----------------------------------------------------|---------------------------------------------|
| React Flow (UI)          | Node/edge rendering, layout, viewport state        | Canonical workflow, validation, history     |
| Yjs (optional)           | Awareness (cursors, presence), optimistic layout   | Durable state, semantic conflict resolution |
| WebSocket/HTTP adapter   | `WorkflowOperation` translation, outbox publishing | GModel sync, layout persistence             |
| Application service      | Validate, apply, checkpoint workflow operations    | UI rendering, JGit internals                |
| `VersionedWorkflowStore` | Durable Git checkpoints, history                   | Editor state, collaboration sessions        |
| `audio-core Workflow`    | Semantic graph, operations, execution snapshots    | UI, persistence, JGit, React, Yjs           |

---

## Migration / fallback

If React Flow proves too difficult to keep server-authoritative (e.g. optimistic-update drift
becomes a recurring bug class), the migration path is:

1. Extract all semantic operation translation into a clean `WorkflowEditorService` that accepts
   only `WorkflowOperation` inputs and returns `Workflow` projections.
2. Replace React Flow with GLSP by pointing GLSP action handlers at `WorkflowEditorService`.
3. The `audio-core` model, DSL serializer and `VersionedWorkflowStore` remain unchanged.

Because the canonical state is already isolated behind `VersionedWorkflowStore` and
`WorkflowOperationLog`, the editor is replaceable without touching the domain layer.
