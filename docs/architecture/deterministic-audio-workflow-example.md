# Deterministic workflow example

The first executable workflow uses existing catalog nodes and explicit metadata:

```text
node.generator (synthetic-signal-generator)
  signal.waveform = sine
  signal.frequency-hz = 1000
  signal.phase-radians = 0
  signal.amplitude = 0.5
  signal.sample-rate-hz = 8000
  signal.channels = 1
  signal.frame-count = 8

edge.generator-gain
  node.generator:signal-out -> node.gain:audio-in

node.gain (gain)
  gain.factor = 2
```

The terminal samples are numerically:

```text
0,
√0.5,
1,
√0.5,
0,
-√0.5,
-1,
-√0.5
```

The run result exposes these values through the bounded hexadecimal preview and provides the stable
SHA-256 digest for the full output block. The preview is diagnostic; the digest and typed execution
status are the machine-oriented evidence.
