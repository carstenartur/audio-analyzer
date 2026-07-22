# Real-world microphone-array localization workflow

This guide describes the supported workflow from a physical multichannel capture device to
reproducible localization evidence. It uses the same acquisition-domain concepts as simulation and
replay, so moving an experiment between source modes does not require a second geometry or metadata
model.

## 1. Discover and select a capture device

`JavaSoundCaptureDeviceDiscovery` enumerates input-capable JavaSound mixers and exposes API-neutral
`CaptureDeviceDescriptor` values. The stable device id includes the provider name, vendor,
description and version so profiles do not depend on a transient combo-box index.

A device profile records:

- the selected device identity;
- sample rate;
- channel count;
- source sample size;
- signed or unsigned PCM interpretation;
- byte order.

The channel count must equal the number of microphones in the array geometry.

## 2. Define the array geometry and channel mapping

Create a `MicrophoneArrayProfile` containing one `Microphone` per synchronized channel. Every
microphone has:

- a stable id;
- a zero-based contiguous channel number;
- a measured two-dimensional position in metres.

The profile also declares a human-facing geometry family: stereo pair, linear, rectangular or
custom. The family is descriptive; the measured microphone coordinates remain the localization
truth.

## 3. Calibrate and validate synchronization

Live localization requires a `MicrophoneArrayCalibration` for the exact geometry and channel
mapping in the profile. The calibration records:

- reference channel;
- static channel offset;
- affine sample-rate drift;
- residual and jitter error estimates;
- gain correction and polarity;
- calibration and expiry times.

Before opening a live source, `MicrophoneArrayReadiness.assess(...)` checks:

- that the profile supports live mode;
- that capture and array channel counts agree;
- that calibration is present and belongs to the same array mapping;
- that the calibration is current;
- that its estimated error remains inside the configured timing budget.

A rejected readiness result prevents `JavaSoundMicrophoneArraySource` from opening the device.
Simulation and replay use deterministic shared-clock evidence unless their source provides a more
specific synchronization assessment.

## 4. Capture through the common source abstraction

`JavaSoundMicrophoneArraySource` implements `MultiChannelAudioSource`, the same interface used by the
simulator. It:

- opens the mixer selected by the persisted device id;
- reads interleaved PCM from `TargetDataLine`;
- decodes it through the existing `SampleDecoder`;
- emits normalized immutable `AudioBlock` values;
- preserves frame indexes, timestamps, format and microphone metadata;
- closes and releases the physical line deterministically.

No JavaSound type crosses into `audio-acquisition` or the experimental localization pipeline.

## 5. Manage an experiment lifecycle

`LocalizationExperiment` associates one profile with one input mode and source reference. Its
ordered lifecycle is:

1. `DEFINED` — array, source and identity are fixed;
2. `CALIBRATED` — readiness evidence has been assessed;
3. `RECORDED` — physical, replay or generated samples are fixed;
4. `LOCALIZED` — tracking snapshots exist;
5. `BENCHMARKED` — outputs have been compared with available evidence;
6. `EXPORTED` — manifests and reports have been written.

Stages advance one step at a time. Additional metadata is stored in deterministic key order, for
example operator id, recording hash, environment description or software revision.

## 6. Persist profiles and reproducibility evidence

`DirectoryMicrophoneArrayProfileStore` writes profiles atomically using
`MicrophoneArrayProfileCodec`. The format is deterministic:

- no generated timestamps;
- stable key ordering;
- complete geometry, hardware and calibration data;
- schema version validation;
- URL-safe value encoding.

`LocalizationExperimentCodec` embeds the complete profile in an experiment manifest. A manifest is
therefore sufficient to reconstruct the array, source mode, calibration and experiment metadata
even if the mutable profile store has changed later.

For workbench simulation runs, `WorkbenchScenarioRunner` now attaches a deterministic
`LocalizationExperiment`. `WorkbenchExperimentExporter` emits both a machine-readable manifest and
a Markdown hardware/profile summary. Live and replay integrations use the same types.

## Practical validation checklist

Before claiming a real localization result, record and verify:

- exact microphone coordinates and orientation;
- channel-to-microphone mapping;
- common hardware clock or measured offset/drift model;
- calibration validity window and error budget;
- sample format and channel count reported by the device;
- source recording hash or live-session identity;
- algorithm parameters and software revision;
- environmental conditions relevant to the speed of sound;
- benchmark or independent reference method, when available.

## Limitations and non-claims

The workflow makes hardware assumptions explicit; it does not make uncalibrated hardware accurate.
JavaSound device identifiers are stable for identical provider metadata but a driver upgrade may
change them and require the profile to be rebound. A single JavaSound line is treated as a
synchronized multichannel device; independent USB microphones do not automatically share a clock.

Real-hardware accuracy still depends on measured geometry, synchronization quality, calibration,
room reflections, signal bandwidth and the selected localization algorithm. The exported manifest
preserves this evidence so a result can be inspected or reproduced instead of being presented as an
unqualified position estimate.
