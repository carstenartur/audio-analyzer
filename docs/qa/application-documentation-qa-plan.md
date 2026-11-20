# Application and documentation QA plan

This plan defines the QA work needed before the application and documentation should be considered
professional-quality. It focuses on the current risk areas: stale documentation, generated screenshots,
visual readability, end-to-end application smoke tests and reproducible release evidence.

## Goals

- Ensure the README and feature documentation describe the current application, not an older snapshot.
- Regenerate all documentation screenshots from the current codebase.
- Visually review screenshots for unreadable labels, overlapping text, clipped axes, empty regions and
  inconsistent styling.
- Add automated guards so documentation images cannot silently become stale or unreadable again.
- Perform manual QA of the main Swing application and the experimental workbenches.
- Record remaining limitations honestly instead of presenting research features as production-ready.

## Scope

### Documentation inventory

Review at least:

- `README.md`
- `ARCHITECTURE.md`
- `ROADMAP.md`
- `docs/development.md`
- `docs/quality.md`
- `docs/QA-FINDINGS.md`
- `docs/features/*.md`
- `docs/plugins/acoustic-localization.md`
- `docs/plugins/acoustic-localization/**/*.md`
- `docs/use-cases/**/*.md`

For each page verify:

- the described feature exists in the current code;
- commands use the current project version or avoid hard-coded versions;
- all links resolve within the repository or to intended external pages;
- experimental limitations are clearly stated;
- referenced screenshots exist and show the described UI state.

### Screenshot inventory

Use the complete [image inventory](documentation-visual-review-2026-11-20.md) and
[screenshot checklist](screenshot-qa-checklist.md). It covers all nine Java2D illustrations and all
11 browser images, including initial load, palette, collaboration/undo and six microphone states.

Java2D images are generated illustrations, not captures of Swing windows. For a new UI instruction,
prefer an actual application/component capture from an executable scenario. Valuable future captures
include document preview/edit/save, the profile/calibration editor, replay selection and recovery.
A missing illustration is not a substitute for a complete text procedure with expected outcomes.

### Visual readability criteria

A documentation screenshot fails QA when any of these are visible:

- text labels overlap or are written on top of each other;
- axis labels or tick labels are clipped;
- legends collide with plotted data in a way that prevents reading the graph;
- controls are cropped or partly outside the image;
- the screenshot contains large unexplained blank areas;
- dark/light contrast is too low for text or key traces;
- the screenshot does not match the feature described next to it.

### Automated checks

`DocImageRendererTest` covers all nine illustration generators. The visual review found that broad
nonblank-region tests missed erased headers/tables and overlapping axis text; targeted regression
assertions now protect those regions and separate text placement. Browser tests assert semantic state
before capture and compare committed baselines. Neither mechanism replaces visual inspection.

For future changes, assert the relevant control/state or layout contract, regenerate the image,
inspect it at readable resolution, and record executed/skip counts for packaged-browser tests.

### Manual application QA matrix

Run the packaged application and inspect:

- Main dashboard: startup, demo mode, live/mocked input, resizing, HiDPI, dark theme readability.
- Waveform / trigger: trigger mode, slope, fallback behavior, overlay readability.
- Spectrum: averaging, peak hold, reset, axis/tick readability.
- Spectrogram: history rendering, labels, color scale readability.
- Recording / replay: record, stop, open, replay completion, large-file warning behavior.
- Evidence export: CSV/PNG/evidence bundle filenames, metadata, readable exported image.
- Plugin menu: plugin discovery, overview view, workbench view, no host dependency leakage.
- Acoustic workbench: scenario run, playback controls, frame selection, budget warnings, exports.
- Imported recording workbench: import errors, empty dataset, selected recording analysis, calibration
  path.
- Error handling: missing files, invalid directories, unsupported devices, cancelled operations.

### Release-quality gate

Before a release or public announcement, require:

- `./mvnw clean verify` passes;
- documentation screenshots have been regenerated from the release candidate;
- all screenshot references resolve;
- the manual QA matrix is executed or explicitly deferred with reasons;
- critical documentation/readability issues are closed;
- remaining research limitations are listed in `docs/QA-FINDINGS.md` or linked issues.

## Current review and remaining acceptance

The [20 November review](documentation-visual-review-2026-11-20.md) records corrected documentation and
image defects. Maintain links, artifact selectors and capability boundaries with the code. Complete
manual device, HiDPI and native-platform checks with a dated matrix before making corresponding
release claims. The current baseline does not establish physical microphone accuracy or Windows/macOS
installation behavior.
