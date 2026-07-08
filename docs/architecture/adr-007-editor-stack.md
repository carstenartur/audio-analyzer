# ADR-007: Editor Stack Selection for Experiment Modeling Workbench

**Issue**: [#221](https://github.com/carstenartur/audio-analyzer/issues/221)  
**Status**: Accepted — React Flow + Yjs  
**Date**: 2026-07-07  
**Accepted**: 2026-07-08  
**Related spikes**: [#219 GLSP spike notes](glsp-spike-notes.md), [#220 React Flow/Yjs spike notes](react-flow-yjs-spike-notes.md)

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

This ADR is **accepted**. The decision is based on the comparable spike evidence from
[#219 GLSP spike notes](glsp-spike-notes.md) and [#220 React Flow/Yjs spike notes](react-flow-yjs-spike-notes.md).

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

## Decision: React Flow + Yjs

**Accepted** based on spike evidence from
[glsp-spike-notes.md](glsp-spike-notes.md) and
[react-flow-yjs-spike-notes.md](react-flow-yjs-spike-notes.md).

### Why React Flow + Yjs keeps cognitive load lower

1. **Single model** — React Flow state is always a derived view of the server projection. There is
   no second canonical model (GModel) that must be kept in sync with `audio-core Workflow`.
2. **Narrow adapter surface** — a developer needs to understand one HTTP/WebSocket adapter and one
   `WorkflowEditorService` class. No extension host, no JSON-RPC protocol, no GModel lifecycle.
3. **Research-scale workbench** — the first workbench is a productivity tool for researchers, not
   an enterprise IDE. React Flow's npm-based setup fits the scope.
4. **Testable without a browser** — the `WorkflowEditorService` can be tested with plain Java unit
   tests asserting on `WorkflowProjection` responses.
5. **Yjs is optional and additive** — awareness/presence can be introduced later without changing
   the server-authoritative design.

### Why GLSP was not chosen

GLSP is architecturally sound and correctly encourages server-authoritative operation flows.
However, for this workbench:

- Setup requires a GLSP server process plus a Theia/VS Code extension host before any graph is
  visible.
- Every mutation must be reflected in both GModel and `audio-core Workflow`; a GModel projection
  bug diverges silently.
- A developer unfamiliar with GLSP needs to learn the JSON-RPC protocol, `GModelState`,
  `OperationHandler` lifecycle and the Sprotty/React client model before writing a test.
- The setup and learning cost is disproportionate to the research-workbench scope.

GLSP remains a valid fallback path (see Migration / fallback below).

### What this decision does not change

- `audio-core Workflow` remains the semantic domain model.
- `WorkflowOperationLog` remains the replay/undo mechanism.
- `VersionedWorkflowStore` remains the persistence facade.
- `WorkflowValidator` remains the type-compatibility authority.
- Layer boundaries enforced by `ArchitectureFitnessTest` remain unchanged.

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

## Acceptance record

This ADR was accepted on 2026-07-08 when:

- [glsp-spike-notes.md](glsp-spike-notes.md) recorded the GLSP spike result (#219);
- [react-flow-yjs-spike-notes.md](react-flow-yjs-spike-notes.md) recorded the React Flow/Yjs
  spike result (#220);
- both spikes used the same backend `WorkflowEditorService` API;
- the decision above explains why React Flow + Yjs keeps cognitive load lower than GLSP for this
  workbench scope.

---

## Migration / fallback

If React Flow proves too difficult to keep server-authoritative, the fallback path is:

1. Extract all semantic operation translation into a clean `WorkflowEditorService` that accepts only `WorkflowOperation` inputs and returns `Workflow` projections.
2. Replace React Flow with GLSP by pointing GLSP action handlers at `WorkflowEditorService`.
3. Keep `audio-core` model, DSL serializer and `VersionedWorkflowStore` unchanged.

If GLSP proves too heavy for the first useful editor, keep React Flow as the UI layer and limit Yjs to awareness/layout helpers until semantic collaboration is implemented through `WorkflowOperation` events.
