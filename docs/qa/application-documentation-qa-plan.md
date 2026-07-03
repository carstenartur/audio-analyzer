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

Regenerate and inspect:

- `docs/images/screenshot.png`
- `docs/images/features/waveform-trigger.png`
- `docs/images/features/spectrum-peak-hold.png`
- `docs/images/features/recording-format.png`
- `docs/images/features/ab-comparison.png`

Additional screenshots should be added for:

- acoustic localization simulation workbench;
- imported recording workbench;
- playback explorer controls;
- generator calibration from imported dataset features;
- plugin menu / contributed workbench discovery if the UI is stable enough.

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

The existing `DocImageRendererTest` only checks the dashboard screenshot dimensions and that some
bright content exists. That is useful as a smoke test, but not enough for professional documentation.
Add checks for:

- all generated images, not only the dashboard screenshot;
- expected dimensions for every image;
- non-blank content for every major panel region;
- text/layout guardrails where practical, e.g. measured text bounds must fit within intended cells;
- CI regeneration check or documented manual regeneration command before release.

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

## Known current risks from inspection

- README and development docs contain hard-coded snapshot-version commands that can become stale.
- `DocImageRenderer` renders fixed-size images with manual coordinates and no systematic text-fit
  guard, so long labels can overlap or be clipped.
- Only the dashboard screenshot currently has a minimal smoke test.
- Feature screenshots and workbench screenshots need explicit visual QA before being used as
  professional project material.
- The repository cannot claim complete application QA until the Swing app and plugin workbenches are
  exercised with a documented manual matrix.

