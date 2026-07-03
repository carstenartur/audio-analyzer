# Feature documentation

These pages document user-visible application features in more depth than the README. They focus on
what a user can do, how the feature behaves, what evidence it can export and where the current limits
are.

## Dashboard and inspection features

- [Oscilloscope-style waveform trigger](oscilloscope-trigger.md) — stabilize a repeating waveform so
  the visible trace does not slide across the panel.
- [Spectrum: peak hold and exponential averaging](peak-hold-and-averaging.md) — combine stable
  steady-state inspection with remembered transient peaks.

## Reproducibility and QA features

- [Recording and replay](recording-and-replay.md) — capture produced `AudioBlock`s into a `.aar` file
  and replay them through the same analysis pipeline.
- [A/B comparison](ab-comparison.md) — compare two recordings and render a Markdown report for QA,
  regression notes or bug tickets.

## Related documentation

- The [README](../../README.md) gives the project overview and quickstart.
- The [development guide](../development.md) explains build, test and screenshot generation.
- The [QA plan](../qa/application-documentation-qa-plan.md) defines the manual and screenshot QA
  expectations for release-quality documentation.
- The [use cases](../use-cases/) directory describes what a measurement can be used for, such as
  stereo localization.

## Documentation quality rule

Whenever a feature changes, update the feature page, regenerate affected screenshots and verify that
labels, legends and axes remain readable. Do not manually edit generated screenshots; fix the renderer
or the UI layout and regenerate the image from code.
