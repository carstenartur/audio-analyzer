# Feature guides

These pages explain user-visible behavior in more depth than the project README. Start with the activity you want to perform.

## Inspect a signal

- [Oscilloscope-style waveform trigger](oscilloscope-trigger.md) — stabilize a repeating waveform so its phase does not slide across the panel.
- [Spectrum peak hold and exponential averaging](peak-hold-and-averaging.md) — separate steady components from remembered transients.

## Reproduce and compare an experiment

- [Recording and replay](recording-and-replay.md) — capture produced `AudioBlock`s into a `.aar` file and replay them through the same analysis pipeline.
- [A/B comparison](ab-comparison.md) — compare two recordings and render a Markdown report for QA, regression notes or experiment records.
- [Workflow history search](workflow-history-search.md) — search indexed checkpoint messages, paths and workflow DSL, then load the exact matching commit.

## Design and share a processing workflow

- [Collaborative workflow design](collaborative-workflows.md) — create or join a session, edit the canonical React Flow graph and use personal or explicit shared semantic undo/redo.

The collaboration guide includes generated screenshots for personal and shared undo previews. Those images are produced only after packaged-application integration tests establish the documented session, revision, target and confirmation state.

## Explore research features

- [Experimental acoustic localization](../plugins/acoustic-localization.md) — simulation, datasets, tracking, benchmark foundations and current physical limitations.
- [Use cases](../use-cases/) — examples of how measurements may be interpreted, including stereo localization.

## Related documentation

- [Getting started](../getting-started.md)
- [Documentation home](../README.md)
- [Architecture](../../ARCHITECTURE.md)
- [Development and validation](../development.md)
- [Application and documentation QA plan](../qa/application-documentation-qa-plan.md)

## Documentation quality rule

Whenever a feature changes:

1. update the user guide and architecture text that describe it;
2. create the documented state through an integration test;
3. assert the relevant semantic state before capture;
4. regenerate the committed screenshot;
5. inspect labels, contrast, clipping and empty states visually;
6. run screenshot verification against the packaged application.

Do not manually edit generated screenshots. Fix the UI, scenario or renderer and regenerate the image from code.
