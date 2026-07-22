# Acoustic localization — research notes

This folder collects the research-oriented documentation for the `audio-experimental-acoustic`
subproject. It complements the user-facing plugin documentation in
[`../README.md`](../README.md) and the runtime tracking architecture described in
[`../tracking.md`](../tracking.md).

The content here is intentionally narrower than a real paper: it is a working outline
that maps research concepts (TDOA, Doppler fusion, frequency tracking) onto the
classes, simulation scenarios and tests that already live in the repository, so the
documentation stays grounded in code that compiles and is exercised by tests.

**Status:** These documents describe both implemented features (tracking pipeline, workbench,
simulation scenarios, evaluation metrics) and exploratory future work (predictive tracking,
event-driven sensing). Each document clearly marks which sections are operational code vs research
proposals.

## Contents

|                                  Document                                  |                                                             Purpose                                                             |                     Status                     |
|----------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------|
| [`paper-outline.md`](paper-outline.md)                                     | Sections of a publishable description of the system, with cross-references to existing modules and tests.                       | Working outline, references implemented code   |
| [`experiments.md`](experiments.md)                                         | Reproducible evaluation scenarios, each mapped to a `SimulationScenarios` entry in the `audio-experimental-acoustic` module.    | Describes implemented scenarios                |
| [`evaluation-metrics.md`](evaluation-metrics.md)                           | Definitions of the metrics used by tests and demos, aligned with `TrackingSnapshot` / `AcousticLocalizationSnapshot`.           | Implemented metrics documented                 |
| [`demo-scenarios.md`](demo-scenarios.md)                                   | Demos that can be shown live using the Swing plugin and `AcousticDebugFrame`.                                                   | Describes operational workbench features       |
| [`predictive-tracking.md`](predictive-tracking.md)                         | Exploratory notes on future-position estimation, latency compensation and confidence-aware prediction.                          | **Future work** — not implemented              |
| [`event-driven-sensing.md`](event-driven-sensing.md)                       | Generic architecture sketch for using the tracker as a trigger source for an external capture system.                           | **Future work** — architectural proposal only  |
| [`research-questions.md`](research-questions.md)                           | Open research questions guiding future experiments.                                                                             | Future work                                    |
| [`physics-limitations.md`](physics-limitations.md)                         | Physical and engineering bounds that constrain every claim in this folder.                                                      | Informational — applies to all implementations |
| [`hardware/microphone-array-setup.md`](hardware/microphone-array-setup.md) | Practical setup notes for real microphone rigs, complementing the simulator.                                                    | Guidance for real-world experiments            |
| [`simulation-datasets/README.md`](simulation-datasets/README.md)           | JSON scenario descriptors with ground truth, derived from `SimulationScenarios`, for benchmark scripts and external evaluation. | Generated from implemented scenarios           |

The implemented TDOA and beamforming families, deterministic noise/reflection benchmarks, strengths,
limitations and explicit non-claims are documented in
[`localization-algorithms.md`](localization-algorithms.md).

## Related documents

- Subproject overview: [`docs/plugins/acoustic-localization.md`](../../acoustic-localization.md)
- Plugin user guide: [`../README.md`](../README.md)
- Tracking pipeline architecture: [`../tracking.md`](../tracking.md)
- Real-world dataset strategy: [`../datasets.md`](../datasets.md)
- Synchronization assumptions: [`../synchronization.md`](../synchronization.md)
- Repository-level architecture: [`../../../architecture/experimental-acoustic-localization.md`](../../../architecture/experimental-acoustic-localization.md)

## Conventions

- Every research claim must point at a class, scenario or test in the
  `audio-experimental-acoustic` module. If the code is not there yet, the document
  must explicitly mark the item as *future work*.
- Numerical constants (frequencies, positions, velocities) follow
  `SimulationScenarios` so that the documentation and the deterministic tests cannot
  drift apart silently.
- Ground-truth datasets live under [`simulation-datasets/`](simulation-datasets/) as
  JSON so that external benchmark scripts can consume them without depending on the
  Java code.

## Explicit non-goals

The work documented in this folder focuses on **localization, tracking,
prediction, synchronization, low-cost sensing and experimental DSP systems**.
It explicitly does **not** aim to:

- affect biological behaviour;
- manipulate animals, insects or any other living subject;
- create acoustic weapons or any device intended to harm;
- provide pest-control systems or repellents.

If a future contribution wants to apply this codebase to one of those domains,
that contribution belongs in a separate project with its own ethical review;
the acoustic subproject here remains a generic measurement and tracking
platform.
