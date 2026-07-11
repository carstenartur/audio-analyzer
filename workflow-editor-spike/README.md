# Workflow Editor MVP — React Flow + WorkflowOperation

This directory contains the client-side Modeling Workbench MVP for the ADR-007
React Flow + narrowly scoped Yjs direction. The Java server side is implemented in
`audio-core/src/main/java/org/hammer/audio/workflow/editor/` and the HTTP
adapter in `audio-app/src/main/java/org/hammer/audio/workflow/editor/http/`.

The MVP keeps React Flow as an adapter. The canonical workflow state remains in
`WorkflowEditorService` / `WorkflowOperationLog` and all semantic changes are
posted as `WorkflowOperation` requests.

## Files

|             File              |                                                      Purpose                                                       |
|-------------------------------|--------------------------------------------------------------------------------------------------------------------|
| `WorkflowEditorComponent.tsx` | React Flow workbench with node palette, typed-port canvas, parameter panel, validation, save/reload and history UI |
| `src/yjsWorkbenchState.ts`    | Executable Yjs spike for cursor awareness and scoped undo/redo of viewport/panel UI state only                     |
| `src/main.tsx`                | Vite/React entry point that mounts `WorkflowEditorComponent`                                                       |
| `index.html`                  | HTML shell for the Vite dev server                                                                                 |
| `package.json`                | npm package with React, React Flow, Yjs, awareness and Vite dependencies                                           |
| `vite.config.ts`              | Vite configuration with `/workflow` proxy to the Java HTTP adapter on port 8080                                    |
| `tsconfig.json`               | TypeScript compiler configuration                                                                                  |

## Running locally

```bash
# From the workflow-editor-spike/ directory:
npm install
npm run dev
```

The Vite dev server starts at `http://localhost:5173` and proxies all
`/workflow` requests to the Java HTTP adapter at `http://localhost:8080`.

To start the Java HTTP adapter, construct `WorkflowEditorHttpAdapter` with a
store-backed `WorkflowEditorService` and call `start(8080)` in audio-app.

## Verification

```bash
# From workflow-editor-spike/:
npm run verify
```

`verify` runs the TypeScript/Vite build. The Java application-service boundary
is covered by Maven tests in `audio-core`.

## HTTP API consumed by the workbench

|                   Endpoint                   |                                        Purpose                                        |
|----------------------------------------------|---------------------------------------------------------------------------------------|
| `GET /workflow/projection`                   | Load current server-authoritative graph projection                                    |
| `GET /workflow/catalog`                      | Load node palette entries from the experiment catalog                                 |
| `GET /workflow/validation`                   | Validate the current graph and show validation feedback                               |
| `POST /workflow/operations`                  | Apply `CreateNode`, `ConnectPorts`, `DisconnectPorts` and `UpdateProperty` operations |
| `POST /workflow/checkpoints`                 | Save/checkpoint the current graph through `VersionedWorkflowStore`                    |
| `GET /workflow/history?branch=main&limit=20` | Show checkpoint history                                                               |
| `POST /workflow/load`                        | Reload a branch head or a specific commit                                             |
| `GET /workflow/snapshot`                     | Preview the deterministic DSL snapshot used for execution handoff                     |

## Issue #220 coverage

- **Minimal graph**: the canvas renders the current `WorkflowProjection`.
- **Typed ports**: React Flow handles are generated from typed port descriptors.
- **Valid and invalid edges**: every connection is posted to the backend and accepted or rejected by `WorkflowEditorService` before the UI projection changes.
- **Parameter edits**: the panel posts `UpdateProperty` operations.
- **Server authority**: React Flow is rebuilt from the returned projection; browser state is never durable truth.
- **Yjs awareness**: `YjsWorkbenchState` carries local/remote user and cursor state through `Awareness`.
- **Scoped undo**: Yjs `UndoManager` tracks viewport and selected-panel changes only.
- **Canonical-state exclusion**: workflow, nodes, edges, operations, DSL and checkpoints are explicitly forbidden from Yjs ownership.

## GLSP comparison evidence

The competing protocol-level GLSP spike is executable in
`audio-core/src/main/java/org/hammer/audio/workflow/editor/glsp/GlspWorkflowAdapter.java`.
Its tests demonstrate typed GModel-shaped projections, valid/invalid edge handling,
parameter edits and edge removal while preserving the same server-authoritative
`WorkflowEditorService` boundary.

## Screenshot documentation

Generated screenshots for this UI should be added through #228. The workbench
uses stable labels and visible regions for the required screenshot scenarios:
node palette, typed ports, parameter panel, validation feedback, save/reload and
history.

## Java unit tests

The server-side `WorkflowEditorService` is tested without a browser in
`audio-core/src/test/java/org/hammer/audio/workflow/editor/WorkflowEditorServiceTest.java`.
The GLSP comparison adapter is tested in
`audio-core/src/test/java/org/hammer/audio/workflow/editor/glsp/GlspWorkflowAdapterTest.java`.
