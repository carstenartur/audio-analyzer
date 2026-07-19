# Audio Analyzer documentation

Audio Analyzer is both an audio-processing workbench and a platform for reproducible, versioned experiments. This documentation is organized by what you want to accomplish rather than by Maven module.

## Start here

- [Getting started](getting-started.md) — build the project, launch the desktop or web workbench and run a first deterministic experiment.
- [Feature guides](features/README.md) — focused guides for signal inspection, recording, comparison and collaborative workflows.
- [Collaborative workflows](features/collaborative-workflows.md) — create sessions, edit a shared graph and use semantic undo/redo safely.
- [Experimental acoustic localization](plugins/acoustic-localization.md) — research capabilities, evidence and current physical limitations.

## Reproduce and compare audio work

- [Recording and replay](features/recording-and-replay.md)
- [A/B comparison reports](features/ab-comparison.md)
- [Workflow history search](features/workflow-history-search.md)
- [Oscilloscope-style waveform trigger](features/oscilloscope-trigger.md)
- [Spectrum peak hold and averaging](features/peak-hold-and-averaging.md)
- [Stereo-localization use case](use-cases/stereo-localization.md)

## Design and collaborate on workflows

- [Collaborative workflows](features/collaborative-workflows.md)
- [React Flow session client architecture](architecture/react-flow-session-client.md)
- [Durable semantic undo and redo](architecture/semantic-undo-redo.md)
- [Two-browser end-to-end evidence](collaboration-e2e.md)
- [Durable full-process restart evidence](durable-restart-e2e.md)
- [Hibernate-backed workflow persistence](workbench-hibernate-persistence.md)

## Extend the platform

- [Plugin development](development/plugin-development.md)
- [Development guide](development.md)
- [Architecture](../ARCHITECTURE.md)
- [Bounded contexts](architecture/bounded-contexts.md)

Plugin implementation details intentionally live outside the project README. The README should help audio-processing users understand the workbench before it asks them to learn the extension API.

## Operate and validate a build

- [Development and local validation](development.md)
- [Quality gates and coverage](quality.md)
- [Workbench screenshot pipeline](workbench-screenshot-pipeline.md)
- [Application and documentation QA plan](qa/application-documentation-qa-plan.md)
- [Release-readiness checklist](qa/release-readiness-checklist.md)
- [Current QA findings](QA-FINDINGS.md)

## Research documentation

The acoustic-localization module is experimental. Its documentation is intentionally explicit about simulation assumptions, datasets, calibration, hardware and error budgets:

- [Plugin overview](plugins/acoustic-localization.md)
- [Detailed plugin documentation](plugins/acoustic-localization/README.md)
- [Synchronization and calibration](plugins/acoustic-localization/synchronization.md)
- [Datasets](plugins/acoustic-localization/datasets.md)
- [Evaluation baseline](plugins/acoustic-localization/evaluation-baseline.md)
- [Tracking](plugins/acoustic-localization/tracking.md)
- [Physics and latency limits](plugins/acoustic-localization/physics-and-latency-limits.md)

## Documentation contract

Public documentation follows four rules:

1. Describe implemented behavior, not intended behavior.
2. Distinguish stable platform capabilities from experimental research.
3. Generate user-interface screenshots from executable integration scenarios.
4. Link detailed architecture and development material instead of turning the README into an API manual.
