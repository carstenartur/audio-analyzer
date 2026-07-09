# Workflow Editor Spike — React Flow + WorkflowOperation

This directory contains the client-side spike for the ADR-007 decision
(React Flow + Yjs direction). The Java server side is implemented in
`audio-core/src/main/java/org/hammer/audio/workflow/editor/` and the HTTP
adapter in `audio-app/src/main/java/org/hammer/audio/workflow/editor/http/`.

## Files

|             File              |                                                 Purpose                                                  |
|-------------------------------|----------------------------------------------------------------------------------------------------------|
| `WorkflowEditorComponent.tsx` | React Flow component rendering typed-port nodes and implementing the server-authoritative update pattern |
| `src/main.tsx`                | Vite/React entry point that mounts `WorkflowEditorComponent`                                             |
| `index.html`                  | HTML shell for the Vite dev server                                                                       |
| `package.json`                | npm package with React, React Flow and Vite dependencies                                                 |
| `vite.config.ts`              | Vite configuration with `/workflow` proxy to the Java HTTP adapter on port 8080                          |
| `tsconfig.json`               | TypeScript compiler configuration                                                                        |

## Running locally

```bash
# From the workflow-editor-spike/ directory:
npm install
npm run dev
```

The Vite dev server starts at `http://localhost:5173` and proxies all
`/workflow` requests to the Java HTTP adapter at `http://localhost:8080`.

To start the Java HTTP adapter, run `WorkflowEditorHttpAdapter.start(8080)` in
audio-app (see the class Javadoc for a self-contained main method example).

## Key architecture points proved by this spike

- **Single model**: React Flow state is always repopulated from the server
  `WorkflowProjection`; no parallel model to synchronise.
- **Typed ports**: each node renders typed `Handle` components coloured by
  `dataType`; the backend `WorkflowValidator` enforces compatibility.
- **Rejection path**: a 422 response leaves the UI on the last accepted
  projection — no state was committed before the server confirmed acceptance.
  No rollback mechanism is needed or present.
- **Server-authoritative edge deletion**: `onEdgesChange` intercepts React
  Flow's "remove" change type and fires `DisconnectPorts` to the server instead
  of removing the edge locally. The edge stays in the UI until the server
  confirms.
- **Yjs boundary**: Yjs (if added) may own cursor/awareness/layout only; it
  must not own canonical workflow edges, nodes, operation ordering, or durable
  history.

## Java unit tests

The server-side `WorkflowEditorService` is tested without a browser in
`audio-core/src/test/java/org/hammer/audio/workflow/editor/WorkflowEditorServiceTest.java`:

- valid edge: `SyntheticSignalGenerator(AudioBlock) → Gain(AudioBlock)` accepted ✓
- invalid edge: `RecordingInput(Dataset) → Gain(AudioBlock)` rejected with
  `WorkflowOperationRejectedException` (type mismatch); log unchanged ✓
- parameter update: `UpdateProperty` on Gain node accepted; property value
  `"1.5"` visible in the returned projection ✓
- edge removal: `DisconnectPorts` removes the edge; returned projection has 0
  edges; operation recorded only after validation passes ✓
- projection shape: node count, handle descriptors, empty edge list ✓

