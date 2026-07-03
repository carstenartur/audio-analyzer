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

| Check | Result | Notes |
|---|---|---|
| Application starts without exception |  |  |
| Demo mode renders waveform, phase, spectrum, spectrogram, measurements and diagnosis |  |  |
| Resize to small/medium/large window keeps labels readable |  |  |
| HiDPI / scaling tested |  |  |
| Dark-theme contrast acceptable |  |  |

## Feature panels

| Check | Result | Notes |
|---|---|---|
| Waveform trigger: auto mode |  |  |
| Waveform trigger: normal mode |  |  |
| Spectrum averaging toggle |  |  |
| Spectrum peak hold toggle/reset |  |  |
| Spectrogram labels and color scale readable |  |  |
| Measurement/diagnosis panels readable under resizing |  |  |

## Recording, replay and export

| Check | Result | Notes |
|---|---|---|
| Start/stop `.aar` recording |  |  |
| Open and replay `.aar` recording |  |  |
| Evidence bundle export |  |  |
| CSV export |  |  |
| PNG export / screenshot capture |  |  |
| A/B comparison report |  |  |

## Plugin and workbench flows

| Check | Result | Notes |
|---|---|---|
| Plugin menu discovers acoustic-localization plugin |  |  |
| Plugin overview opens |  |  |
| Acoustic localization workbench opens |  |  |
| Simulation scenario runs to completion |  |  |
| Playback controls step through frames |  |  |
| Budget warning display is understandable |  |  |
| Workbench Markdown/CSV/JSON exports readable |  |  |
| Imported recording workbench handles invalid path |  |  |
| Imported recording workbench handles empty/partial dataset |  |  |
| Generator calibration path is visible and understandable |  |  |

## Documentation screenshots

| Image | Regenerated | Visual QA result | Notes |
|---|---:|---|---|
| `docs/images/screenshot.png` |  |  |  |
| `docs/images/features/waveform-trigger.png` |  |  |  |
| `docs/images/features/spectrum-peak-hold.png` |  |  |  |
| `docs/images/features/recording-format.png` |  |  |  |
| `docs/images/features/ab-comparison.png` |  |  |  |
| Acoustic localization workbench screenshot |  |  |  |
| Imported recording workbench screenshot |  |  |  |

## Documentation review

| Page | Result | Notes |
|---|---|---|
| `README.md` |  |  |
| `ARCHITECTURE.md` |  |  |
| `ROADMAP.md` |  |  |
| `docs/development.md` |  |  |
| `docs/quality.md` |  |  |
| `docs/QA-FINDINGS.md` |  |  |
| `docs/features/README.md` |  |  |
| `docs/features/*.md` |  |  |
| `docs/plugins/acoustic-localization.md` |  |  |
| `docs/plugins/acoustic-localization/**/*.md` |  |  |
| `docs/use-cases/**/*.md` |  |  |

## Findings

### Blocking

- 

### Non-blocking

- 

### Deferred with rationale

- 
