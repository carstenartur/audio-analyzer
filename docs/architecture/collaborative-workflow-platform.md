# Collaborative Workflow Platform Architecture

Status: Planning document  
Primary decision record: [`adr-006-versioned-collaborative-workflow-store.md`](adr-006-versioned-collaborative-workflow-store.md)  
First verification step: [`jgit-storage-hibernate-spike.md`](jgit-storage-hibernate-spike.md)

## Purpose

This document describes the target platform for drawing, editing, versioning and executing workflow graphs. It is the scenario-level architecture that explains how the future graphical editor should work.

The normative architectural decision is ADR-006. If this document and ADR-006 disagree, ADR-006 wins.

## Relationship to existing architecture

Audio Analyzer already has the stable workflow foundation in `audio-core`:

- immutable design-time workflow model;
- semantic `WorkflowOperation` edits;
- `WorkflowOperationLog` for deterministic replay and undo;
- execution snapshots and execution plans isolated from editing state.

The collaborative editor must extend that foundation instead of introducing a second workflow model.

```text
audio-core workflow model
    -> semantic workflow operations
    -> workflow operation log
    -> deterministic workflow DSL
    -> versioned workflow persistence facade
    -> DB-backed JGit storage
    -> collaboration/event layer
    -> web graph editor
```

## Goals

- browser-based graph editor for workflow nodes and typed ports;
- reusable non-UI workflow domain model;
- private and shared editing sessions;
- personal and explicitly shared undo modes;
- deterministic replay and audit through semantic operations;
- durable version history through JGit-backed checkpoints;
- searchable workflow history through future Hibernate Search projections;
- reproducible execution from stable `ExecutionSnapshot` values;
- no dependency from `audio-core` workflow model to Swing, JGit, persistence or web frameworks.

## Non-goals

- no immediate rewrite of the Swing application;
- no large `workflow.json` as the canonical long-term format;
- no direct dependency from Audio Analyzer public APIs to `org.eclipse.jgit.internal.*`;
- no assumption that Git alone solves live collaboration;
- no assumption that a browser CRDT document is the permanent source of truth.

## Target architecture

```text
Browser graph editor / desktop WebView
    -> workflow collaboration API
    -> workflow application service
    -> WorkflowOperationLog
    -> audio-core Workflow model
    -> WorkflowValidator
    -> deterministic workflow DSL
    -> VersionedWorkflowStore facade
    -> jgit-storage-hibernate / hibernate-jgit-store
    -> database-backed JGit objects, refs and reflog
    -> Hibernate Search projections
    -> transactional outbox
    -> event broker / WebSocket clients
```

## Source of truth

|     Concern     |               Source of truth                |                          Notes                           |
|-----------------|----------------------------------------------|----------------------------------------------------------|
| Workflow graph  | `audio-core` workflow model                  | Immutable, framework-independent, validated server-side. |
| Workflow edit   | `WorkflowOperation` / `WorkflowOperationLog` | Used for replay, audit and undo.                         |
| Durable version | JGit commit produced from deterministic DSL  | Git stores checkpoints, not every UI gesture.            |
| Live event      | Operation event from transactional outbox    | Broker/WebSocket transports committed facts only.        |
| Presence        | Collaboration session state                  | Cursors, selection and viewport are not workflow state.  |
| Rendering       | Web editor state                             | UI state is derived and disposable.                      |

## Collaboration modes

The user must be able to choose how a drawing is shared.

### `PRIVATE_WORKSPACE`

Only the actor sees their current changes. Undo/redo is personal. Publishing or merging into a shared workflow is explicit.

### `SHARED_SESSION_PERSONAL_UNDO`

Participants see the same live workflow. Each actor's undo stack contains only their own undoable operations. This is the recommended default for live collaboration.

### `SHARED_SESSION_SHARED_UNDO`

Participants share one room-level undo stack. Undo may revert another user's operation, so the UI must show exactly which operation and actor will be affected before the user confirms.

## Operation model

All durable workflow changes should be expressible as semantic operations:

```text
CreateNode
DeleteNode
MoveNode
RenameNode
UpdateNodeProperty
CreateEdge
DeleteEdge
UpdatePortType
CreateComment
ResolveConflict
UndoOperation
RedoOperation
```

These operations are required for:

- deterministic undo/redo;
- integration tests;
- audit logs;
- semantic merge;
- conflict analysis;
- collaboration replay after reconnect.

Undo and redo are also operations. They must not silently rewrite history.

## Git/JGit versioning

Git stores stable workflow checkpoints:

- commit;
- branch;
- merge;
- cherry-pick;
- revert;
- history;
- comparison;
- restoration.

Git is not the live-collaboration mechanism. Live collaboration uses semantic operations and session events. Git receives stable snapshots/checkpoints derived from the workflow model and deterministic DSL.

The JGit storage layer is not implemented directly in Audio Analyzer. It should be consolidated as a separate `jgit-storage-hibernate` / `hibernate-jgit-store` component and accessed through a narrow `VersionedWorkflowStore` facade.

## Storage format direction

The canonical persisted representation should be deterministic and reviewable. JSON may be useful for APIs or debugging, but a naive large `workflow.json` should not become the long-term source format.

Preferred direction:

```text
workflow model
    -> deterministic workflow DSL
    -> Git blob/tree/commit
    -> DB-backed JGit store
```

Layout and presence must stay separate from the workflow graph:

```text
workflow.apflow      semantic graph
layout.aplayout      node positions, grouping, viewport defaults
metadata.toml/json   name, tags, description, authoring metadata
```

## Web editor direction

The future graphical editor is web-first, but the Swing app remains valid during the transition.

Accepted direction (ADR-007):

- React Flow + Yjs is the accepted editor stack for the first workbench MVP (see [`adr-007-editor-stack.md`](adr-007-editor-stack.md));
- Yjs may own awareness, presence and viewport/layout helpers; it must not own canonical workflow
  state, durable history, `WorkflowOperation` ordering, or semantic conflict resolution;
- React Flow state is always derived from the server `WorkflowProjection`; no optimistic semantic
  commits; rejected operations leave the UI on the last accepted projection;
- `WorkflowEditorHttpAdapter` in `audio-app` is the HTTP bridge between the React Flow client and
  the `WorkflowEditorService` in `audio-core`;
- none of these client-side tools may become the canonical workflow persistence layer by accident;
- GLSP remains documented as the evaluated fallback path if server-authoritativeness proves harder
  to maintain in React.

### Editor stack selection criteria

The final editor stack should be chosen by evidence from spikes, not by document age.

|              Criterion              |            GLSP-first spike            |                    React Flow/Yjs spike                     |
|-------------------------------------|----------------------------------------|-------------------------------------------------------------|
| Server-authoritative workflow model | Natural fit; model service is central. | Must be enforced by adapter/API discipline.                 |
| Fast UX iteration                   | More upfront architecture.             | Strong advantage.                                           |
| Typed ports and semantic validation | Natural fit.                           | Feasible, but custom code.                                  |
| Multi-user awareness/presence       | Needs integration.                     | Strong advantage with Yjs-style helpers.                    |
| Personal undo in shared sessions    | Domain operation model still required. | Yjs can help, but domain semantics must still be tested.    |
| Deterministic replay/audit          | Domain operation model required.       | Domain operation model required.                            |
| Avoiding browser state as truth     | Natural fit.                           | Explicit guardrail required.                                |
| Long-term maintainability           | Good if GLSP complexity is acceptable. | Good if the adapter remains small and server-authoritative. |

Recommended decision rule:

```text
If GLSP proves too heavy for the first useful editor, prefer React Flow/Yjs for the UI layer.
If React Flow/Yjs cannot keep server-authoritative operations and deterministic replay clean, prefer GLSP.
In both cases, audio-core Workflow + WorkflowOperationLog + VersionedWorkflowStore remain the canonical model.
```

The backend remains authoritative for workflow validation, operation ordering and durable checkpoints.

## Conflict examples

### Different nodes changed

```text
User A changes FFT.windowSize
User B changes Classifier.threshold
```

Expected result: no semantic conflict.

### Same property changed differently

```text
Base: FFT.windowSize = 2048
User A: FFT.windowSize = 4096
User B: FFT.windowSize = 1024
```

Expected result: semantic property conflict, not a text conflict.

### Node deleted while another user connects it

```text
User A deletes Node X
User B creates Edge from Node X to Node Y
```

Expected result: invalid edge or semantic conflict report.

### Endpoint property changed while another user connects it

```text
User A changes a property on Node Y that affects compatibility
User B connects an edge to Node Y
```

Expected result: backend validation or semantic merge marks the edge for review.

## Execution model

A workflow run always uses a stable snapshot:

```text
current editable workflow state
    -> validation
    -> ExecutionSnapshot
    -> ExecutionPlan
    -> ExecutionContext
    -> ExecutionResult
```

Users may continue editing while a run executes. The running execution remains tied to the snapshot that started it.

## Event transport

The broker is transport only. It is not the source of truth.

Recommended pattern:

```text
DB transaction:
  append WorkflowOperation
  update workflow/session projection
  create optional Git checkpoint
  insert outbox event
commit

outbox dispatcher:
  publish event to broker/WebSocket clients
```

ActiveMQ Artemis, another JMS/AMQP broker, or a simple WebSocket adapter can be evaluated behind a `WorkflowEventBus` abstraction.

## Testing strategy

Unit tests:

- workflow validation;
- port compatibility;
- node property validation;
- DSL serialization;
- semantic diff;
- semantic merge;
- execution planning.

Collaboration tests with simulated users:

- concurrent node moves;
- concurrent property edits;
- delete-vs-modify;
- connect-vs-parameter-change;
- undo-vs-remote-change;
- offline/online reconnect;
- commit while others edit.

Fuzz tests should apply random operations and assert that the workflow remains structurally valid or produces explicit validation errors.

End-to-end tests should eventually open two browser sessions, edit the same workflow, verify propagation, run undo and execute a saved snapshot.

## Implementation order

1. Finish the JGit/Hibernate storage spike.
2. Define the `VersionedWorkflowStore` facade.
3. Implement the minimal `Input -> Gain -> Output` workflow roundtrip.
4. Add deterministic DSL serialization and reload.
5. Add semantic diff for the vertical slice.
6. Build the GLSP-first editor spike and, if feasible, a constrained React Flow/Yjs editor spike against the same backend API.
7. Choose the editor stack using the criteria above.
8. Add collaboration sessions and event transport.
9. Add personal/shared undo behavior.
10. Add Hibernate Search projections for workflow history.

## Related documents

- [`README.md`](README.md) — map of architecture documents.
- [`bounded-contexts.md`](bounded-contexts.md) — module and package boundaries.
- [`adr-006-versioned-collaborative-workflow-store.md`](adr-006-versioned-collaborative-workflow-store.md) — accepted decision with spike gates.
- [`jgit-storage-hibernate-spike.md`](jgit-storage-hibernate-spike.md) — first technical verification step.

