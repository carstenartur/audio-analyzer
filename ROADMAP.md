# Roadmap

This roadmap lists open work. Implemented capabilities are documented in the README and in the feature
and plugin guides. The roadmap is intentionally forward-looking: it should help contributors choose
what to do next, not repeat completed history.

## Release-quality documentation and QA

The current public-quality priority is the documentation and screenshot overhaul tracked in #198.

Near-term goals:

1. Keep `./mvnw clean verify` green, including Spotless Markdown checks.
2. Regenerate all checked-in documentation screenshots from the current codebase.
3. Visually review screenshots for overlapping labels, clipped axes, unreadable legends and misleading
   empty states.
4. Replace stale version-specific command examples with release-tolerant instructions.
5. Complete a dated manual QA evidence file under `docs/qa/` before public release messaging.

Relevant documents:

- [Application and documentation QA plan](docs/qa/application-documentation-qa-plan.md)
- [Screenshot QA checklist](docs/qa/screenshot-qa-checklist.md)
- [Manual application QA template](docs/qa/manual-application-qa-template.md)
- [Release-readiness checklist](docs/qa/release-readiness-checklist.md)

## Product hardening

Open hardening work:

- reduce Checkstyle, PMD and SpotBugs findings below the committed CI baseline;
- raise JaCoCo thresholds gradually after adding behavior-focused tests;
- add an offline documentation link checker;
- add more layout tests for Swing panels that contain dynamic labels or resizing behavior;
- keep generated documentation images reproducible and reviewable.

## Application UX

The Swing dashboard is functional but should be hardened as a user-facing tool:

- verify startup, demo mode, recording/replay and export workflows on a packaged build;
- test common desktop sizes and HiDPI scale factors;
- review long labels before adding them to fixed-width controls;
- add visual or panel-level tests where generated screenshots cannot cover runtime states.

## Architecture

Current architecture work should protect the separation between stable platform code and experimental
research code:

- keep stable plugin contracts in `audio-plugin-api`;
- keep experimental acoustic-localization code inside `audio-experimental-acoustic` until APIs and
  evidence justify promotion;
- continue enforcing bounded contexts with architecture fitness tests;
- resolve the `org.hammer.audio` split package before any JPMS migration;
- decide whether demo-signal types belong in a stable API package or remain application-specific.

## Recording, replay and evidence export

Recording and replay are core reproducibility features. Useful next steps:

- add richer evidence-bundle metadata, including build/version and capture settings;
- expand A/B comparison reports with configurable pass/fail thresholds;
- add replay-driven integration tests around end-to-end analysis publication;
- document expected behavior for very large recordings and memory limits.

## Experimental acoustic localization

The acoustic-localization module remains a research plugin, not a production detector. It is valuable
because it makes algorithm assumptions executable and benchmarkable.

Implemented research foundations include:

- deterministic simulation scenarios with moving sources, reflections and noise;
- multi-peak detection, frequency clustering, TDOA estimation and grid beamforming;
- Kalman-based source tracking and benchmark metrics;
- simulation and imported-recording workbenches;
- local HumBugDB import, feature extraction and rule-based classification baseline;
- synthetic-vs-real comparison and generator calibration infrastructure.

Open technical research work is tracked in issues:

- #136 — synchronization and calibration framework for microphone-array experiments;
- #138 — algorithm improvements beyond baseline GCC-PHAT and grid beamforming;
- #139 — complete real-world microphone-array workflow from hardware to localization.

Research directions after the documentation QA pass:

1. make timing assumptions explicit in code and documentation;
2. add calibrated array profiles and deterministic offset/drift tests;
3. improve TDOA confidence, sub-sample precision and reflection handling;
4. expand benchmark evidence with real recordings and reproducible fixtures;
5. document supported and unsupported hardware paths without overstating reliability.

