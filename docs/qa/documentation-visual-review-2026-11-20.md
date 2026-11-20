# Documentation and visual review — 20 November 2026

## Scope and evidence

The review started from master `8f71fdffe54e3f53a574f268af221cdd9be05b2d`. All **20 committed PNGs**
were opened individually and inspected for readable labels, overlap, clipping, contrast, populated
state and agreement with their captions. The source/documentation audit covered the **84 tracked
Markdown files** and all **306 local link/image occurrences** present at that baseline. Those are
baseline inventory counts; this report and its links increase the current totals.

The review found genuine defects despite green CI: erased Java2D headers/table regions, colliding
axis labels, an outdated recording-format diagram, four broken research anchors, incorrect launcher
commands, stale capability claims and incomplete first-use procedures. Corrected illustrations are
regenerated from source and reviewed with the changes in this commit; screenshots are never retouched.

### What the image evidence means

- **Eight web captures** show actual packaged-application states established by integration scenarios.
- **Three web captures** render fixed typed runtime snapshots. Their captions identify them as examples;
  they do not establish measured storage throughput, hardware accuracy or a real disk-full event.
- **Nine Java2D images** are generated analysis/format illustrations. They are not captures of the Swing
  window. Real algorithm outputs and illustrative dataset/calibration values are labelled accordingly.

The unmodified web baselines were reproduced on the source baseline by the
[screenshot workflow](https://github.com/carstenartur/audio-analyzer/actions/runs/44812252318): the
final verify step executed 15 tests, with zero failures, errors or skips. Shared Undo and Microphone
Blocked both had 0% difference. The separate
[collaboration run](https://github.com/carstenartur/audio-analyzer/actions/runs/44812252585) executed
seven explicit scenarios without failures or skips. The CI links identify the tested revision;
newer commits require their own applicable checks.

## Complete image inventory

Paths below are relative to `docs/`. Every file is included, even when it is not embedded in a user
guide. A full-page browser capture may require opening the original image to read it on a phone.
Captions and adjacent numbered steps remain readable without enlarging the picture.

### Browser captures

|                                                       Image                                                        |               Scenario               |                                          Visual result and scope                                          |
|--------------------------------------------------------------------------------------------------------------------|--------------------------------------|-----------------------------------------------------------------------------------------------------------|
| [initial-load.png](../assets/screenshots/workbench/initial-load.png)                                               | `WorkbenchInitialLoadIT`             | PASS — Actual read-only orientation graph; readable nodes/ports and readiness.                            |
| [node-palette.png](../assets/screenshots/workbench/node-palette.png)                                               | `WorkbenchInitialLoadIT`             | PASS — Complete palette through Evidence Export; disabled state is intentional before joining.            |
| [collaboration-session.png](../assets/screenshots/workbench/collaboration-session.png)                             | `WorkbenchCollaborationScreenshotIT` | PASS — Actual live session, accepted revision and two nodes; scroll position shows session controls.      |
| [collaboration-personal-undo-preview.png](../assets/screenshots/workbench/collaboration-personal-undo-preview.png) | `WorkbenchHistoryScreenshotIT`       | PASS — Authoritative preview, actor/target and confirmation readable; background is intentionally dimmed. |
| [collaboration-shared-undo-preview.png](../assets/screenshots/workbench/collaboration-shared-undo-preview.png)     | `WorkbenchHistoryScreenshotIT`       | PASS — Explicit target and acknowledgement readable; confirmation disabled before acknowledgement.        |
| [microphone-experiment-ready.png](../assets/screenshots/workbench/microphone-experiment-ready.png)                 | `MicrophoneExperimentWebIT`          | PASS — Actual preflight; Start enabled; host-dependent values masked.                                     |
| [microphone-experiment-blocked.png](../assets/screenshots/workbench/microphone-experiment-blocked.png)             | `MicrophoneExperimentWebIT`          | PASS — Actual unavailable device/profile blockers; Start disabled; host values masked.                    |
| [microphone-experiment-complete.png](../assets/screenshots/workbench/microphone-experiment-complete.png)           | `MicrophoneExperimentWebIT`          | PASS — Actual 16,000-frame simulation; all seven track rows and complete export readable.                 |
| [microphone-experiment-recording.png](../assets/screenshots/workbench/microphone-experiment-recording.png)         | `MicrophoneExperimentWebIT`          | PASS — Typed runtime fixture; persistent recording metrics and Stop control readable.                     |
| [microphone-experiment-degraded.png](../assets/screenshots/workbench/microphone-experiment-degraded.png)           | `MicrophoneExperimentWebIT`          | PASS — Typed runtime fixture; processing/storage alerts and acknowledgement readable.                     |
| [microphone-experiment-incomplete.png](../assets/screenshots/workbench/microphone-experiment-incomplete.png)       | `MicrophoneExperimentWebIT`          | PASS — Typed runtime fixture; dropped/gap counts and incomplete export readable.                          |

Magenta masks cover only the explicitly documented volatile host values. The ready/blocked states
show no source estimates because no localization run has occurred. The complete image preserves all
reported tracks, including multiple hypotheses at the same coordinate; it is not a claim that each
row represents a separate verified physical source. See the
[health capture contract](../features/experiment-health.md#verification-and-screenshot-coverage).

### Generated illustrations

All nine use `org.hammer.tools.DocImageRenderer`. The renderer and affected plotting helpers are
corrected at their source; regression assertions protect erased content and separate label regions.

|                                              Image                                               |           Type            |                                                      Finding addressed                                                       |                                                Re-review                                                 |
|--------------------------------------------------------------------------------------------------|---------------------------|------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------|
| [screenshot.png](../images/screenshot.png)                                                       | Dashboard illustration    | Header/controls were overpainted; axis labels collided; spectrogram was synthetic rather than the calculated signal history. | PASS — Header and table visible; separate labels; calculated 440-Hz energy in a labelled 0–2-kHz detail. |
| [features/waveform-trigger.png](../images/features/waveform-trigger.png)                         | Trigger illustration      | Sample tick and axis title collided.                                                                                         | PASS — Status, ticks and axis titles occupy separate readable regions.                                   |
| [features/spectrum-peak-hold.png](../images/features/spectrum-peak-hold.png)                     | Spectrum illustration     | Frequency axis title collided with centre tick; Y labels lacked separate margins.                                            | PASS — Legend and axes readable; spectra use the labelled dB scale.                                      |
| [features/recording-format.png](../images/features/recording-format.png)                         | Recording-format diagram  | Described legacy AAR1 as current and omitted AAR2 finalization/integrity footer.                                             | PASS — Current AAR2 layout, completion markers, integrity footer and legacy boundary are readable.       |
| [features/ab-comparison.png](../images/features/ab-comparison.png)                               | A/B spectrum illustration | Axis ticks/titles and delta annotation collided.                                                                             | PASS — Both plots and the calculated 430.7-Hz FFT-peak difference are readable.                          |
| [features/simulation-workbench.png](../images/features/simulation-workbench.png)                 | Scenario illustration     | Axis/legend margins needed separation; scene is illustrative.                                                                | PASS — Source/microphone labels, room scale and legend do not overlap.                                   |
| [features/imported-recording-workbench.png](../images/features/imported-recording-workbench.png) | Dataset illustration      | Left feature table/title were overpainted; example numbers were not clearly labelled.                                        | PASS — Full table visible; invented values and absence of measured validation explicitly stated.         |
| [features/playback-explorer.png](../images/features/playback-explorer.png)                       | Playback illustration     | Header overpainted; axis/title collided; right-hand playback value clipped.                                                  | PASS — Full time value visible; plot/cursor/slider use the same 23.2/92.9-ms buffer positions.           |
| [features/generator-calibration.png](../images/features/generator-calibration.png)               | Calibration illustration  | Title overpainted, percentage axis reversed relative to bars, example provenance unclear.                                    | PASS — Title and legend visible; 0% is below 100%; fictional values are clearly labelled.                |

All nine final regenerated images were opened individually by both the implementer and an independent
reviewer. A second generation in the same Java/font environment reproduced all nine files byte for
byte. Eight new regression tests cover retained content, text geometry and the calculated
spectrogram frequency. The layout assertions first reproduced six failures; the frequency assertion
separately reproduced the misplaced synthetic band. The first PR run also exposed a font-dependent
Y-axis collision that was reproduced locally with a wider logical font: two pixels of overlap instead
of the required four-pixel gap. Axis margins now use the actual tick widths and font ascent/descent;
the strict pixel assertion remains in place and a wide-label case checks that the margin grows.
The final focused renderer/plot suite executed 48 tests with zero failures, errors or skips under
both the default and the previously failing wider font configuration.

## Reproducible user workflows

The updated guides state prerequisites, exact controls, expected outcomes and failure/recovery paths.

|                  Goal                   |                                  Complete procedure                                  |                                 Expected result / boundary                                  |
|-----------------------------------------|--------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------|
| First signal and recording              | [Getting started](../getting-started.md#explore-a-signal-in-the-desktop-workbench)   | Explicit Demo mode/start, growing recording, finalized `.aarec`, inspection and replay      |
| First browser computation               | [Generator/gain walkthrough](../getting-started.md#design-a-workflow-in-the-browser) | All eight properties supplied, ports connected, ready preflight and eight-frame result      |
| Microphone simulation/live/replay       | [Microphone guide](../features/microphone-experiments.md)                            | Saved profile, correct duration, preflight, final state and evidence archive                |
| Inspect and recover a recording         | [Recording guide](../features/recording-and-replay.md)                               | Original preserved, recovered file re-inspected; gaps remain blockers for strict replay     |
| Open/edit/save/reopen a portable setup  | [Document guide](../features/portable-experiment-documents.md)                       | Separate supported desktop and browser paths; original and complete envelope retained       |
| Collaborate and undo with two clients   | [Collaboration guide](../features/collaborative-workflows.md)                        | Mode chosen before Create; same server/session, live revision convergence and explicit undo |
| Search/load/compare/restore checkpoints | [History guide](../features/workflow-history-search.md)                              | Hibernate prerequisite, exact commits and explicit standalone-editor scope                  |
| Provision persistent mode               | [Persistence guide](../workbench-hibernate-persistence.md)                           | Four ordered migration histories, validate, save/restart/reload                             |

The UI text and workflows were traced against production source. Existing packaged browser and
backend tests provide runtime evidence for their exercised scenarios. This review is not a new claim
that every numbered Swing action was manually clicked on every supported operating system.

The public document CLI was also exercised with `docs/examples/minimal.audioexp`: validate the
original, normalize to a separate file, validate that copy and normalize it again. All four commands
returned exit code 0; both normalized copies were byte-identical and the source was retained.

## Documentation corrections

Current README/indexes, architecture/roadmap and QA status now link the microphone, health and portable
setup guides. Old research/future-work statements were reconciled with implemented behavior. The
wrong `-web.jar`, obsolete version-specific launch and ambiguous desktop JAR glob were replaced.

The four broken research anchors were redirected to existing limitations sections. The final offline
check covered 85 Markdown files, 468 local link/image occurrences and 54 Markdown fragment references,
with zero missing targets or broken anchors. It used a CommonMark/GFM parser and excluded code fences
and external URLs; external sites and DOI availability are outside this repository link check.
Historical July QA reports remain dated records and now link to the current status.

Screenshot setup includes reactor preparation, Chromium installation, verify/update separation and
explicit executed/skip-count review. The central checklist now includes both image directories.

## Remaining limits

Documentation completeness means the implemented workflows and their limits can be followed; it does
not add unsupported product paths. Current limits include live-session-to-portable-document export,
the standalone 3 × 2 m search envelope, historical replay calibration time semantics and recovery of
missing samples. See [current QA findings](../QA-FINDINGS.md).

Physical array accuracy, long-duration real capture, HiDPI/resize usability and native Windows/macOS
installation were not performed in this review. Linux package association and packaged browser tests
have separate automated evidence. Additional real UI captures for document dialogs, profile editing
and recovery are useful future documentation, but are not represented by the Java2D illustrations.
