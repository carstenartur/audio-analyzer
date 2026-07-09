# Workflow Editor Spike — React Flow + WorkflowOperation

This directory contains the client-side skeleton for the ADR-007 spike
(React Flow + Yjs direction). The Java server side is implemented in
`audio-core/src/main/java/org/hammer/audio/workflow/editor/`.

## Files

|             File              |                                                      Purpose                                                      |
|-------------------------------|-------------------------------------------------------------------------------------------------------------------|
| `WorkflowEditorComponent.tsx` | Minimal React Flow component rendering typed-port nodes and demonstrating the server-authoritative update pattern |

## Running locally

```bash
# From this directory
npm create vite@latest . -- --template react-ts
npm install reactflow
# Copy WorkflowEditorComponent.tsx into src/
# Replace src/App.tsx with: import WorkflowEditorComponent from './WorkflowEditorComponent'; export default WorkflowEditorComponent;
npm run dev
```

The component fetches the initial `WorkflowProjection` from the backend at startup.
All node/edge mutations go through `POST /workflow/operations` and the state is
updated from the server response only — React Flow's local state is never the
source of truth.

## Key architecture points proved by this spike

- **Single model**: React Flow state is always repopulated from the server
  `WorkflowProjection`; no parallel model to synchronise.
- **Typed ports**: each node renders typed `Handle` components coloured by
  `dataType`; the backend `WorkflowValidator` enforces compatibility.
- **Rejection path**: a 422 response discards the optimistic edge and restores
  the last accepted projection; no "state roll-back" is needed because no edge
  was committed to state in the first place.
- **Yjs boundary**: Yjs (if added) may own cursor/awareness/layout only; it
  must not own canonical workflow edges or nodes.

## Java unit tests

The server-side `WorkflowEditorService` is tested without a browser in
`audio-core/src/test/java/org/hammer/audio/workflow/editor/WorkflowEditorServiceTest.java`:

- valid edge: `SyntheticSignalGenerator(AudioBlock) → Gain(AudioBlock)` accepted ✓
- invalid edge: `RecordingInput(Dataset) → Gain(AudioBlock)` rejected with
  `WorkflowOperationRejectedException` (type mismatch) ✓
- parameter update: `UpdateProperty` on Gain node accepted and projected ✓
- projection shape: node count, handle descriptors, empty edge list ✓

