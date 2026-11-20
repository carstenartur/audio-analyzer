# Manual application QA evidence template

Use this template for release-candidate manual QA. Copy it to a dated file, for example
`docs/qa/manual-application-qa-YYYY-MM-DD.md`, and fill in the results.

## Build under test

- Date:
- Commit SHA:
- Version / tag:
- OS:
- Java version:
- Display / scale factor:
- Maven command used:
- Application launch command:

## Summary

- Overall result: PASS / PASS WITH KNOWN LIMITATIONS / FAIL
- Blocking issues:
- Non-blocking issues:
- Screenshots regenerated: yes / no
- Documentation reviewed: yes / no

## Main dashboard

- [ ] Application starts without exception — Result: / Notes:
- [ ] Demo mode renders waveform, phase, spectrum, spectrogram, measurements and diagnosis — Result:
  / Notes:
- [ ] Resize to small/medium/large window keeps labels readable — Result: / Notes:
- [ ] HiDPI / scaling tested — Result: / Notes:
- [ ] Dark-theme contrast acceptable — Result: / Notes:

## Feature panels

- [ ] Waveform trigger: auto mode — Result: / Notes:
- [ ] Waveform trigger: normal mode — Result: / Notes:
- [ ] Spectrum averaging toggle — Result: / Notes:
- [ ] Spectrum peak hold toggle/reset — Result: / Notes:
- [ ] Spectrogram labels and color scale readable — Result: / Notes:
- [ ] Measurement/diagnosis panels readable under resizing — Result: / Notes:

## Recording, replay and export

- [ ] Start/stop `.aar` recording — Result: / Notes:
- [ ] Open and replay `.aar` recording — Result: / Notes:
- [ ] Evidence bundle export — Result: / Notes:
- [ ] CSV export — Result: / Notes:
- [ ] PNG export / screenshot capture — Result: / Notes:
- [ ] A/B comparison report — Result: / Notes:

## Plugin and workbench flows

- [ ] Plugin menu discovers acoustic-localization plugin — Result: / Notes:
- [ ] Plugin overview opens — Result: / Notes:
- [ ] Acoustic localization workbench opens — Result: / Notes:
- [ ] Simulation scenario runs to completion — Result: / Notes:
- [ ] Playback controls step through frames — Result: / Notes:
- [ ] Budget warning display is understandable — Result: / Notes:
- [ ] Workbench Markdown/CSV/JSON exports readable — Result: / Notes:
- [ ] Imported recording workbench handles invalid path — Result: / Notes:
- [ ] Imported recording workbench handles empty/partial dataset — Result: / Notes:
- [ ] Generator calibration path is visible and understandable — Result: / Notes:

## Documentation screenshots and illustrations

Copy the complete [current image inventory](documentation-visual-review-2026-11-20.md), then add any
newly introduced image. Record one row per file; a historical pass does not cover a changed PNG.

|  Image   |               Scenario/renderer               | Exact code revision | Regenerated? |   Visual result    |               Notes               |
|----------|-----------------------------------------------|---------------------|--------------|--------------------|-----------------------------------|
| filename | actual capture / typed fixture / illustration | commit SHA          | yes/no       | PASS/FAIL/DEFERRED | clipping, contrast, labels, scope |

- [ ] All images in both documentation image directories are inventoried.
- [ ] Every changed image has been opened and inspected at readable resolution.
- [ ] Captions distinguish actual state, fixed fixtures and illustrations.
- [ ] Browser scenario counts and skip counts are recorded.

## Documentation review

- [ ] `README.md` — Result: / Notes:
- [ ] `ARCHITECTURE.md` — Result: / Notes:
- [ ] `ROADMAP.md` — Result: / Notes:
- [ ] `docs/development.md` — Result: / Notes:
- [ ] `docs/quality.md` — Result: / Notes:
- [ ] `docs/QA-FINDINGS.md` — Result: / Notes:
- [ ] `docs/features/README.md` — Result: / Notes:
- [ ] `docs/features/*.md` — Result: / Notes:
- [ ] `docs/plugins/acoustic-localization.md` — Result: / Notes:
- [ ] `docs/plugins/acoustic-localization/**/*.md` — Result: / Notes:
- [ ] `docs/use-cases/**/*.md` — Result: / Notes:

## Findings

### Blocking

- None recorded yet.

### Non-blocking

- None recorded yet.

### Deferred with rationale

- None recorded yet.

