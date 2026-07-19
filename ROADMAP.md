# Roadmap

This roadmap lists open work from the current implemented baseline. Completed collaboration, persistence and UI foundations are documented in the README and architecture guides rather than repeated as future plans.

## Current baseline

The repository currently provides:

- desktop signal inspection, recording/replay and evidence export;
- immutable workflow models and deterministic semantic operations;
- packaged React Flow workflow editing;
- session lifecycle, ordered SSE, presence and revision-safe commands;
- durable personal/shared undo and redo with previews and conflicts;
- Hibernate-backed session recovery, migrations and transactional outbox;
- JGit-backed workflow checkpoint integration;
- executable two-browser collaboration coverage for live convergence and undo/redo;
- generated documentation screenshots for stable UI states.

This baseline is useful, but it is not yet the complete workflow platform described by epic #239.

## Priority 1 — complete cross-process durability evidence

Issue #249 remains open after the live two-browser stage.

The next stage must run the packaged application in durable Hibernate mode, stop the complete process and restart it against the same database. It should prove:

- session, operation identity, revision and event sequence recovery;
- actor history and undo/redo capability recovery;
- command idempotency after restart;
- pending transactional outbox dispatch after restart;
- migration plus Hibernate `validate` startup;
- checkpoint and old-commit loading when the checkpoint lifecycle is included;
- useful browser, server and database diagnostics on failure.

The test must continue using the shared `SessionFactory`, Hibernate-backed collaboration store and Hibernate-backed JGit store. It must not introduce raw JDBC or a parallel test persistence model.

## Priority 2 — semantic diff and merge

Issue #246 owns deterministic workflow comparison, three-way merge and conflict resolution.

Required outcomes:

- semantic rather than line-oriented diffs;
- typed conflicts for modify/modify, delete/modify, delete/connect and stable-id collisions;
- deterministic auto-merge for independent changes;
- explicit auditable resolution choices;
- validation before committing the resolved workflow;
- a React Flow conflict panel without JGit/Hibernate knowledge;
- exact resulting commit identity and reload.

## Priority 3 — searchable workflow history

Issue #247 owns rebuildable search projections over versioned workflow history.

Required outcomes:

- reuse of `jgit-storage-hibernate-search`;
- generic commit/path/author/message/content search from the shared library;
- Audio Analyzer-specific workflow/node/property projections only where necessary;
- deterministic full rebuild from Git authority;
- branch, author, time, workflow, node and property filters;
- exact matching commit load;
- clear index-unavailable versus history-lost behavior;
- no second Hibernate/Search bootstrap or raw-JDBC search path.

Search is derived state and must remain disposable without losing authoritative workflow history.

## Priority 4 — execute immutable workflow snapshots truthfully

Epic #248 and children #273–#275 own actual workflow execution.

### #273 — run orchestration and lifecycle

Introduce immutable source selection, stable run identity, expected-revision checks, backend ports, lifecycle, cancellation and REST contracts. The existing status-only dry run must remain labeled as simulation.

### #274 — real deterministic audio computation

Execute at least:

```text
Synthetic Signal Generator -> Gain
```

through a real backend and assert the numerical output and reproducibility fingerprint.

### #275 — production run UX

Add preflight, provenance, progress, cancellation, result and failure views to the React Flow workbench. Editing may continue, but it must never mutate the captured run input.

The epic is complete only when simulation and actual computation are visibly distinct.

## Product documentation and usability

Documentation should continue to serve audio-processing users first:

- maintain a short task-oriented README;
- keep getting-started and feature guides synchronized with the packaged product;
- keep plugin implementation material in dedicated development pages;
- generate screenshots from integration scenarios;
- reject screenshots with clipped controls, unreadable text or misleading empty states;
- record a dated manual QA pass before release-facing claims.

New stable UI states should receive selectors, assertions and generated documentation in the same change.

## Desktop audio workbench

Useful hardening work includes:

- packaged-build QA for demo, capture, recording, replay and export;
- HiDPI and common desktop-size review;
- explicit handling of long labels and dynamic layout;
- larger-recording memory and streaming guidance;
- richer evidence metadata including software version and capture settings;
- configurable pass/fail criteria for A/B reports.

## Plugin ecosystem

The stable plugin API should evolve conservatively:

- publish a compatibility policy before promising third-party binary stability;
- provide a minimal standalone reference plugin;
- validate contribution id uniqueness and descriptor/documentation quality;
- clarify runtime classpath packaging for external plugins;
- prefer UI-independent contributions where web/headless use is expected;
- avoid moving experimental acoustic assumptions into the stable API.

## Experimental acoustic localization

The localization module remains research-grade.

Open research issues include:

- #136 — synchronization and calibration framework;
- #138 — algorithm improvements beyond baseline GCC-PHAT and grid beamforming;
- #139 — complete real-world microphone-array workflow.

Research priorities:

1. make timing and geometry assumptions executable;
2. add calibrated array profiles and offset/drift fixtures;
3. improve confidence, sub-sample TDOA and reflection handling;
4. expand real-recording benchmark evidence;
5. document supported and unsupported hardware paths;
6. avoid species or safety claims unsupported by calibrated experiments.

## Release-quality gate

A public release should require:

- `./mvnw clean verify`;
- CodeQL;
- relevant Docker/Chromium integration workflows;
- reproducible screenshot verification;
- migration/schema-validation checks for persistent mode;
- visual review of generated images;
- a dated manual QA record;
- explicit stable/experimental boundaries and known limitations.

The roadmap should be updated when an issue closes so completed work moves into feature or architecture documentation rather than remaining presented as future intent.
