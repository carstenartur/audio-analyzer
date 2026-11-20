# Roadmap

This page describes the implemented baseline and the remaining product/research boundaries as of
20 November 2026. Closed implementation issues are described in the feature guides, not listed as
future work. The [current QA findings](docs/QA-FINDINGS.md) distinguish observed defects, source-audited
limits and hardware checks that have not been performed.

## Implemented baseline

|                            Capability                            |                                        Maintained guide and evidence                                         |
|------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|
| Signal inspection, recording, integrity inspection and recovery  | [Recording and replay](docs/features/recording-and-replay.md)                                                |
| Simulation, configured host capture, replay, health and evidence | [Microphone experiments](docs/features/microphone-experiments.md)                                            |
| Portable setup documents, plugin schemas and migrations          | [Portable experiment documents](docs/features/portable-experiment-documents.md)                              |
| Server-issued identities and revision-safe collaboration         | [Collaboration](docs/features/collaborative-workflows.md), [identity policy](docs/collaboration-identity.md) |
| Personal/shared semantic undo and redo                           | [Semantic history](docs/architecture/semantic-undo-redo.md)                                                  |
| Hibernate/JGit persistence and full-process restart              | [Operations](docs/workbench-hibernate-persistence.md), [restart E2E](docs/durable-restart-e2e.md)            |
| Exact checkpoint comparison and validated three-way merge        | [Semantic merge](docs/architecture/semantic-workflow-merge.md)                                               |
| Combined generic/semantic history search and rebuild             | [History search](docs/features/workflow-history-search.md)                                                   |
| Immutable source capture and deterministic audio computation     | [Generator/gain example](docs/architecture/deterministic-audio-workflow-example.md)                          |
| Consumer-owned shared-storage upgrade contract                   | [Runtime contract](docs/workbench-hibernate-persistence.md#shared-persistence-runtime)                       |

## Workflow integration boundaries

The browser portable-document workspace, live collaboration aggregate and stored Git checkpoints
remain distinct sources. The browser currently has no command that exports its active collaboration
graph as a complete `.audioexp` document. A live session also does not create a Git checkpoint through
the standalone checkpoint button. Future integration should capture an explicit immutable source,
preserve the complete document envelope and retain server-side authorization and attribution.

The production computation backend currently executes the documented synthetic sine/gain chain.
Adding a catalog node to the UI does not make it executable. Additional typed executors should bring
numerical tests, capability preflight and output evidence together.

## Experimental acoustic work

Persisted geometry, measured calibration, single-device JavaSound capture, recording/replay and
sub-sample GCC-PHAT in the simulation workbench are implemented. Remaining research work includes:

- measured accuracy and long-duration stability on synchronized physical arrays;
- independent-device synchronization and continuous clock reconstruction;
- room/search-envelope controls beyond the current standalone 3 × 2 m two-dimensional grid;
- broader algorithm and real-recording benchmark evidence;
- historical calibration time semantics for replay;
- validated handling of reflections, difficult noise conditions and multi-source ambiguity.

The [microphone guide](docs/features/microphone-experiments.md) states current parameter defaults,
calibration requirements and replay limits. Species classification and safety claims require their
own measured evidence.

## Desktop and documentation hardening

The complete [image inventory and visual review](docs/qa/documentation-visual-review-2026-11-20.md)
distinguishes application captures from deterministic illustrations. Further release qualification
should cover real devices, native installations, HiDPI, window resizing and representative long
recordings. Browser snapshots do not establish those hardware/platform results.

Useful extensions include configurable A/B acceptance thresholds and further actual UI captures for
portable-document editing, profile calibration and recovery. Add these with executable scenarios
when those interfaces change; do not substitute hand-drawn controls for an observed application state.

## Plugin ecosystem

Keep extension contracts independent of the host. Future third-party compatibility promises need a
versioning policy and independent reference plugin. Versioned document contributions already have
schema, migration and fixture contracts in the [plugin guide](docs/development/plugin-development.md).

## Release-quality gate

A release requires the full Maven verification, applicable packaged-browser/storage/native checks,
visual image review, documented platform/hardware results and clear current limitations. Follow the
[release checklist](docs/qa/release-readiness-checklist.md). A green automated build does not replace
unperformed physical-device or manual platform acceptance.
