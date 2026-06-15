# Experimental Acoustic Localization

This is the entry page for the optional acoustic-localization plugin. The plugin is a research/demo
extension for microphone-array experiments with weak, intermittent or insect-like sound sources. It
is **not** a production mosquito detector, species classifier or validated tracking product.

## Status at a glance

|     Property     |                                          Value                                           |
|------------------|------------------------------------------------------------------------------------------|
| Module           | `audio-experimental-acoustic`                                                            |
| Plugin API       | `audio-plugin-api`                                                                       |
| Discovery        | Java `ServiceLoader` via `META-INF/services/org.hammer.audio.plugin.AudioAnalyzerPlugin` |
| Host integration | `audio-app` depends on the concrete plugin at runtime only                               |
| Stability        | Experimental research code; stable reusable primitives stay in core modules              |

## What is implemented

- **interactive simulation workbench** — run any of nine deterministic scenarios through the full
  tracking pipeline directly from the Plugins menu, with live per-frame log, 2-D room map and
  Markdown / CSV / JSON export;
- plugin descriptor, menu/view contributions and generic host integration;
- insect-like demo presets and frequency peak/cluster tracking;
- cross-correlation and GCC-PHAT TDOA experiments;
- delay-and-sum beamforming and 2D geometry experiments;
- Doppler estimation, multi-sensor velocity reconstruction and tracking pipeline;
- deterministic simulation scenarios and research notes.

## Important limitations

- no guaranteed mosquito detection or species classification;
- no guaranteed exact AR overlay; any display-time prediction is model-dependent;
- synthetic validation does not prove real-world reliability;
- reflections, noise, microphone mismatch and multiple weak sources can dominate results;
- accurate TDOA requires calibrated geometry and a shared sample clock across channels;
- `SampleClock` currently records nominal timing only and does not compensate drift or USB buffering
  jitter.

## Detailed documentation

- [**How to run the workbench**](acoustic-localization/README.md#how-to-run-the-workbench)
- [Plugin details, pipeline and boundaries](acoustic-localization/README.md)
- [Physics and latency limits](acoustic-localization/physics-and-latency-limits.md)
- [Synchronization requirements](acoustic-localization/synchronization.md)
- [Tracking pipeline](acoustic-localization/tracking.md)
- [Real-world dataset strategy](acoustic-localization/datasets.md)
- [Research notes and datasets](acoustic-localization/research/README.md)

