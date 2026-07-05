# Architecture documentation map

This directory contains architecture documents with different levels of authority. The goal is to keep workflow-drawing, collaboration and storage documents consistent instead of letting parallel concepts drift apart.

## Reading order for workflow drawing and collaboration

1. [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) — repository-wide module architecture and core boundary rules.
2. [`bounded-contexts.md`](bounded-contexts.md) — explicit bounded contexts, package boundaries and dependency directions.
3. [`adr-006-versioned-collaborative-workflow-store.md`](adr-006-versioned-collaborative-workflow-store.md) — accepted decision for the versioned collaborative workflow store.
4. [`collaborative-workflow-platform.md`](collaborative-workflow-platform.md) — scenario-level target architecture for drawing, collaboration, undo and execution.
5. [`jgit-storage-hibernate-spike.md`](jgit-storage-hibernate-spike.md) — first technical spike that must decide whether the DB-backed JGit store can be consolidated without an Audio Analyzer specific JGit fork.

## Authority levels

|                      Document                       | Authority |                      Purpose                      |
|-----------------------------------------------------|-----------|---------------------------------------------------|
| `ARCHITECTURE.md`                                   | High      | Stable module overview and architectural goals.   |
| `bounded-contexts.md`                               | High      | Enforced context and dependency boundaries.       |
| `adr-006-versioned-collaborative-workflow-store.md` | Decision  | Accepted direction with spike gates.              |
| `collaborative-workflow-platform.md`                | Planning  | Explains the end-to-end workflow editor platform. |
| `jgit-storage-hibernate-spike.md`                   | Spike     | Defines the next technical verification step.     |

If planning documents conflict with ADR-006 or the bounded-context rules, ADR-006 and the bounded-context rules win.

## Current decisions for workflow drawing

- The existing `audio-core` workflow model remains the semantic domain foundation.
- Graph editing is expressed as semantic `WorkflowOperation` values.
- Undo/redo is operation-based, not Git-commit-based.
- Git/JGit stores durable checkpoints and history, not live collaboration state.
- The DB-backed JGit store should be consolidated outside Audio Analyzer and accessed through a narrow facade.
- The future graph editor is web-first, while the existing Swing application remains valid during transition.
- GLSP is the preferred serious model-driven editor spike, but React Flow/Yjs remains a valid candidate if a spike proves it can preserve the server-authoritative operation model with less implementation complexity.
- The event broker transports committed facts only; persistence uses a transactional outbox.

## Document hygiene rules

- Do not introduce another workflow model in UI or persistence documentation.
- Do not put JGit internals into Audio Analyzer public APIs.
- Keep layout, presence and viewport state separate from the semantic workflow graph.
- Preserve older proposals as explicitly evaluated options when they may still be the best implementation choice.
- Link new workflow-drawing documents from this README.
- When an ADR changes the direction, update `collaborative-workflow-platform.md` in the same PR.

