# Workflow Editor MVP — React Flow + WorkflowOperation

This directory contains the client-side Modeling Workbench MVP for the ADR-007
React Flow + Yjs direction. The Java server side is implemented in
`audio-core/src/main/java/org/hammer/audio/workflow/editor/` and the HTTP
adapter in `audio-app/src/main/java/org/hammer/audio/workflow/editor/http/`.

The MVP keeps React Flow as an adapter. The canonical workflow state remains in
`WorkflowEditorService` / `WorkflowOperationLog` and all semantic changes are
posted as `WorkflowOperation` requests.

## Files

|             File              |                                                      Purpose                                                       |
|-------------------------------|--------------------------------------------------------------------------------------------------------------------|
| `WorkflowEditorComponent.tsx` | React Flow workbench with node palette, typed-port canvas, parameter panel, validation, save/reload and history UI |
| `src/main.tsx`                | Vite/React entry point that mounts `WorkflowEditorComponent`                                                       |
| `index.html`                  | HTML shell for the Vite dev server                                                                                 |
| `package.json`                | npm package with React, React Flow and Vite dependencies                                                           |
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

## Issue #210 MVP coverage

- **Create graph visually**: the canvas renders the current `WorkflowProjection`.
- **Add nodes from palette**: palette buttons post `WorkflowOperation.CreateNode`.
- **Connect compatible ports**: React Flow connections post `ConnectPorts` and update only from the server response.
- **Reject invalid connections**: 422 responses are shown as visible validation feedback; local state is not committed first.
- **Parameter panel**: selected node properties are edited with `UpdateProperty` and shown in the returned projection.
- **Save/checkpoint**: the save panel calls `/workflow/checkpoints`.
- **Reload**: branch and commit reload go through `/workflow/load`.
- **History view**: `/workflow/history` is rendered as a minimal commit list with reload buttons.
- **Canonical state rule**: React Flow state is rebuilt from `WorkflowProjection`; UI code does not access DSL, JGit or persistence internals.

## Screenshot documentation

Generated screenshots for this UI should be added through #228. The workbench
uses stable labels and visible regions for the required screenshot scenarios:
node palette, typed ports, parameter panel, validation feedback, save/reload and
history.

## Java unit tests

The server-side `WorkflowEditorService` is tested without a browser in
`audio-core/src/test/java/org/hammer/audio/workflow/editor/WorkflowEditorServiceTest.java`:

- valid edge: `SyntheticSignalGenerator(AudioBlock) -> Gain(AudioBlock)` accepted;
- invalid edge: `RecordingInput(Dataset) -> Gain(AudioBlock)` rejected with
  `WorkflowOperationRejectedException`; log unchanged;
- parameter update: `UpdateProperty` on Gain node accepted and visible in the returned projection;
- edge removal: `DisconnectPorts` removes the edge; operation recorded only after validation passes;
- checkpoint/load/history roundtrip through `InMemoryVersionedWorkflowStore`;
- snapshot export for execution handoff;
- input validation guards for blank branch/ref and invalid history limit.

