# QA findings and remaining work

Current documentation review: **20 November 2026**, based on master
`8f71fdffe54e3f53a574f268af221cdd9be05b2d` and the corrections committed with this report.
See the [dated documentation and image record](qa/documentation-visual-review-2026-11-20.md) for
scope, image provenance, findings and verification boundaries. Earlier dated QA reports are historical.

## Implemented and verified baseline

The normal reactor has nine modules, including `audio-experiment-document`. The packaged application
provides desktop signal inspection, portable documents, standalone microphone experiments, browser
collaboration, immutable computation, semantic merge and combined history search.

On the baseline commit, all seven GitHub checks completed successfully. Maven executed 1,216 tests
with no failures, errors or skips, including five PostgreSQL container cases. The final screenshot
step executed 15 tests and the separate collaboration scenario step executed seven, both without
failures or skips. These counts describe the identified baseline, not every future build.

Evidence:

- [Maven verification](https://github.com/carstenartur/audio-analyzer/actions/runs/44812252467)
- [Browser and screenshot verification](https://github.com/carstenartur/audio-analyzer/actions/runs/44812252318)
- [Collaboration and full-process restart](https://github.com/carstenartur/audio-analyzer/actions/runs/44812252585)
- [Packaged storage contract](https://github.com/carstenartur/audio-analyzer/actions/runs/44812252353)
- [Linux package association](https://github.com/carstenartur/audio-analyzer/actions/runs/44812252374)

Durable full-process restart, indexed search, semantic merge and actual generator/gain execution are
implemented; the older references to those items as pending have been removed from current guides.

## Findings addressed by the documentation review

- Correct desktop/workbench artifact selection and exact first-use controls.
- Complete simulation, record/replay/recovery, document, collaboration and history procedures.
- Explicit distinction between portable document, collaboration and checkpoint state.
- Correct four-phase migration ownership and separate legacy adoption flags.
- Complete image inventory, actual-capture versus illustration labels, and visual review.
- Java2D background/label layout defects and stale recording-format illustration.
- Current `.aarec` files included in desktop open/compare chooser filters.
- Broken research anchors and obsolete capability/roadmap statements.

## Current product boundaries

### Browser document and session integration

The portable-document panel exports its standalone document workspace. It does not export edits from
the live collaboration canvas. Desktop workflow-DSL edits can preserve and save the complete portable
envelope. The [document guide](features/portable-experiment-documents.md) describes separate supported
round trips. Live-session persistence and explicit Git checkpoints also remain separate contexts.

### Execution and acoustic limits

The graph computation backend supports the documented sine-source/gain chain. Other catalog nodes
can be represented but are rejected by capability preflight until they have executors.

The standalone acoustic route currently uses a fixed two-dimensional search envelope of x = 0–3 m,
y = 0–2 m and fixed analysis defaults. Editing microphone positions does not change that envelope.
It provides a single host-device path; independent-device capture and continuous clock repair are
not implemented guarantees. See the [microphone guide](features/microphone-experiments.md).

### Replay of historical calibration

Source inspection shows that replay can pass nominal-clock preflight while the runner later checks
an attached calibration against the current wall clock. An expired historical calibration can
therefore reject a run. This review did not reproduce that case on physical hardware. Preserve the
original profile and dates; changing timestamps to make old evidence appear valid is not a remedy.
A future correction needs an explicit recorded-time contract and a focused regression scenario.

### Recovery and evidence

Recovery preserves readable complete blocks and their original frame indices. It does not restore
missing samples. Standalone replay still rejects continuity gaps after recovery. Failed evidence
finalization does not grant a complete export. Current guides distinguish these outcomes.

## Unperformed platform and hardware acceptance

The automated runs and image review do not establish:

- acoustic accuracy or long-duration stability on a physical microphone array;
- real device permission, disconnect and exclusive-access behavior on every operating system;
- native Windows/macOS installation and file association;
- interactive Swing behavior across HiDPI and common resizing configurations.

Linux package association and actual packaged browser scenarios have their own automated evidence.
Record the remaining manual checks with the [manual QA template](qa/manual-application-qa-template.md)
when suitable hardware/platforms are available.

## Continuing engineering work

Keep static-analysis baselines narrow, raise coverage through behavior tests and preserve framework
boundaries. `org.hammer.audio` still spans app/DSP code, and selected legacy desktop models remain.
Before a release, run the [release checklist](qa/release-readiness-checklist.md), review changed images,
and record the exact revision and executed/skipped scenarios.
