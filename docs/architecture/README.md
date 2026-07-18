# Architecture documentation map

This directory contains architecture documents with different levels of authority. The goal is to keep workflow-drawing, collaboration and storage documents consistent instead of letting parallel concepts drift apart.

## Reading order for workflow drawing and collaboration

1. [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) — repository-wide module architecture and core boundary rules.
2. [`bounded-contexts.md`](bounded-contexts.md) — explicit bounded contexts, package boundaries and dependency directions.
3. [`adr-006-versioned-collaborative-workflow-store.md`](adr-006-versioned-collaborative-workflow-store.md) — accepted decision for the versioned collaborative workflow store.
4. [`collaborative-workflow-platform.md`](collaborative-workflow-platform.md) — scenario-level target architecture for drawing, collaboration, undo and execution.
5. [`adr-007-editor-stack.md`](adr-007-editor-stack.md) — accepted decision: React Flow as the production editor adapter, with optional scoped Yjs helpers and GLSP as fallback.
6. [`jgit-storage-hibernate-spike.md`](jgit-storage-hibernate-spike.md) — completed consolidation proof and current shared-store boundary.
7. [`../workbench-hibernate-persistence.md`](../workbench-hibernate-persistence.md) — production/development startup and shared Hibernate persistence-context contract.

## Experiment Modeling Workbench documents

8. [`experiment-workflow-model-mapping.md`](experiment-workflow-model-mapping.md) — spike mapping of existing workflow classes to experiment configurations (issue #214).
9. [`experiment-node-catalog.md`](experiment-node-catalog.md) — first experiment node catalog with typed ports, valid and invalid connection examples (issue #215).
10. [`glsp-spike-notes.md`](glsp-spike-notes.md) — GLSP spike result: rendering, edge operations, parameter edits, integration cost (issue #219).
11. [`react-flow-yjs-spike-notes.md`](react-flow-yjs-spike-notes.md) — React Flow/Yjs spike result: rendering, edge operations, parameter edits, Yjs boundary evaluation (issue #220).
12. [`../../audio-web-editor/README.md`](../../audio-web-editor/README.md) — maintained production frontend, reproducible build and Spring Boot packaging contract.
13. [`../../workflow-editor-spike/README.md`](../../workflow-editor-spike/README.md) — historical spike evidence retained without a second executable source tree.
14. [`session-event-streaming.md`](session-event-streaming.md) — accepted SSE event, replay, sequence/revision and slow-client cleanup contract (issue #242).

## Authority levels

|                      Document                       |  Authority   |                                    Purpose                                     |
|-----------------------------------------------------|--------------|--------------------------------------------------------------------------------|
| `ARCHITECTURE.md`                                   | High         | Stable module overview and architectural goals.                                |
| `bounded-contexts.md`                               | High         | Enforced context and dependency boundaries.                                    |
| `adr-006-versioned-collaborative-workflow-store.md` | Decision     | Accepted direction with spike gates.                                           |
| `collaborative-workflow-platform.md`                | Planning     | Explains the end-to-end workflow editor platform.                              |
| `adr-007-editor-stack.md`                           | Accepted ADR | React Flow production adapter decision and GLSP fallback.                      |
| `jgit-storage-hibernate-spike.md`                   | Implemented  | Records the external store release and enforced downstream boundary.           |
| `workbench-hibernate-persistence.md`                | Operations   | Documents persistence modes, package access and startup configuration.         |
| `experiment-workflow-model-mapping.md`              | Spike        | Maps existing workflow classes to experiment configs.                          |
| `experiment-node-catalog.md`                        | Catalog      | First experiment node catalog with typed ports.                                |
| `glsp-spike-notes.md`                               | Spike        | GLSP rendering, operation and integration-cost evidence.                       |
| `react-flow-yjs-spike-notes.md`                     | Spike        | React Flow/Yjs rendering and boundary evidence.                                |
| `audio-web-editor/`                                 | Implemented  | Maintained React Flow source, frontend verification and packaged resource JAR. |
| `workflow-editor-spike/`                            | Historical   | Read-only evidence from the editor comparison.                                 |
| `session-event-streaming.md`                        | Implemented  | SSE event contract, bounded replay, snapshot fallback and transport cleanup.   |

If planning documents conflict with ADR-006, ADR-007 or the bounded-context rules, the accepted ADRs and bounded-context rules win.

## Current decisions for workflow drawing

- The existing `audio-core` workflow model remains the semantic domain foundation.
- Graph editing is expressed as semantic `WorkflowOperation` values.
- React Flow is the maintained production rendering/input adapter in `audio-web-editor`.
- Yjs is optional for non-semantic awareness or UI helpers and is not a mandatory production dependency.
- Undo/redo is operation-based, not Git-commit-based.
- Git/JGit stores durable checkpoints and history, not live collaboration state.
- The DB-backed JGit store remains outside Audio Analyzer and is accessed through a narrow facade.
- The graph editor is web-first, while the existing Swing application remains valid during transition.
- The event broker transports committed facts only; persistence uses a transactional outbox.

## Document hygiene rules

- Do not introduce another workflow model in UI or persistence documentation.
- Do not put JGit internals into Audio Analyzer public APIs.
- Keep layout, presence and viewport state separate from the semantic workflow graph.
- Preserve older proposals as explicitly evaluated options when they remain useful evidence.
- Link new workflow-drawing documents from this README.
- When an ADR changes the direction, update `collaborative-workflow-platform.md` in the same PR.

