# Deterministic audio workflow backend

Issue #274 introduces the first workflow backend that performs real offline audio computation. It
replaces the production simulation adapter without changing the run REST contract introduced by issue
273.

## Supported vertical slice

```text
SyntheticSignalGenerator -> Gain [-> Gain ...]
```

The backend accepts one synthetic sine source followed by one or more linear gain nodes. The graph
must be one connected, linear chain with exactly one terminal gain node. Other catalog node types are
rejected before dispatch with the stable `UNSUPPORTED_NODE` diagnostic.

## Parameters

Executable parameters are stored in node metadata using the constants in
`ExperimentNodeParameters`.

Synthetic source parameters:

- `signal.waveform` (`sine`)
- `signal.frequency-hz`
- `signal.phase-radians`
- `signal.amplitude`
- `signal.sample-rate-hz`
- `signal.channels`
- `signal.frame-count`

Gain parameter:

- `gain.factor`

There are no implicit execution defaults. Missing, malformed, non-finite or out-of-range values are
reported as node-specific preflight violations. Frequency must be below Nyquist. The total number of
samples is bounded before allocation.

## Execution architecture

`audio-core` continues to own immutable run input, snapshot, plan and result contracts. It has no
dependency on `audio-dsp` or the concrete backend.

`audio-dsp` owns the adapter implementation:

- `DeterministicAudioWorkflowExecutionBackend`
- `DeterministicAudioNodeExecutorRegistry`
- `SyntheticSignalNodeExecutor`
- `GainNodeExecutor`
- `GainProcessor`

Node type dispatch is registry-based. Adding another executable node requires a new typed executor and
registry entry; it does not require a switch in the workflow domain model.

## Determinism and evidence

The source reuses `SineGenerator`, including an explicit initial phase. Generated chunks are copied
into a canonical `AudioBlock` with frame index and timestamp fixed independently of wall-clock time.
Gain processing reuses `GainProcessor` and saturates to the normalized range `[-1, 1]`.

Terminal output evidence includes:

- backend version
- node and port identity
- sample rate, channel count and frame count
- minimum, maximum, mean and RMS
- a bounded hexadecimal sample preview
- SHA-256 over `audio-block-f32be-planar-v1`

The digest covers sample rate, channels, source sample width, frame count and every planar IEEE-754
float sample in big-endian bit order. Byte-identical input under the same backend version therefore
produces the same digest.

## Cancellation and failure

Both source generation and gain processing use bounded 4096-frame chunks and check cooperative
cancellation between chunks. Cancellation produces a terminal `CANCELLED` result and is never
reported as completed.

A node runtime failure produces a terminal result with the failing node marked `FAILED`; downstream
nodes are marked `SKIPPED`. Artifacts retain the failing node id, node type, exception class, message
and skipped node ids.

## Deliberate boundaries

The first backend is offline and deterministic. It does not schedule microphones or sound cards,
distribute work, resume in-flight jobs after restart, or support the entire experiment catalog. The
simulation backend remains available for isolated contract tests, but production wiring selects the
computation backend.
