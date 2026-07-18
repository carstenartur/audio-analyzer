# Historical Workflow Editor Spike

This directory is retained as historical evidence for ADR-007 and the comparison that selected React Flow as the production browser editor.

The executable TypeScript/Vite source that originally lived here was promoted into the maintained Maven module:

```text
audio-web-editor/
```

Do not add production code, package manifests or generated assets below this directory. The maintained module owns:

- the pinned Node/npm and frontend dependency toolchain;
- React Flow application source;
- type checking, unit tests and architecture-boundary linting;
- deterministic Vite production builds;
- packaging under `/workbench-ui` for `audio-app`.

Historical design conclusions remain documented in:

- `docs/architecture/adr-007-editor-stack.md`;
- `docs/architecture/react-flow-yjs-spike-notes.md`.

The accepted boundary remains unchanged: the browser renders server projections and submits semantic commands, while validation, ordering, history and persistence remain server-authoritative. Yjs is optional for non-semantic UI state and is not a mandatory dependency of the maintained production frontend.
