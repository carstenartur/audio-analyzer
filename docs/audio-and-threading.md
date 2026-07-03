# Audio Capture & Threading

This page documents how to configure audio capture, how the runtime is structured across threads, and how diagnostic logging is wired up.

It reflects the current `AudioBlock` + `AudioRingBuffer` architecture. For the legacy
`WaveformModel`-centric API and migration notes, see [`MIGRATION.md`](MIGRATION.md).

## Audio Configuration

Audio capture is configured via constructor parameters in `AudioCaptureServiceImpl`:

|     Parameter      |   Type    |                              Description                              |
|--------------------|-----------|-----------------------------------------------------------------------|
| `sampleRate`       | `float`   | Sample rate in Hz (e.g. `16000.0f`, `44100.0f`).                      |
| `sampleSizeInBits` | `int`     | Bits per sample (e.g. `8`, `16`).                                     |
| `channels`         | `int`     | `1` = mono, `2` = stereo.                                             |
| `signed`           | `boolean` | `true` for signed samples, `false` for unsigned.                      |
| `bigEndian`        | `boolean` | Byte order for multi-byte samples.                                    |
| `divisor`          | `int`     | Buffer size divisor (≥ 1). Buffer = `line.getBufferSize() / divisor`. |

Higher sample rates give better frequency resolution. The capture buffer size is `line.getBufferSize() / divisor`, so a larger `divisor` yields smaller buffers (lower latency, higher CPU) while a smaller `divisor` yields larger buffers (higher latency, more efficient). Higher sample rates may require larger buffers to maintain the same time window of audio.

Example — CD-quality mono:

```java
AudioCaptureServiceImpl service = new AudioCaptureServiceImpl(
    44100.0f,  // sampleRate
    16,        // sampleSizeInBits
    1,         // channels
    true,      // signed
    false,     // little-endian
    8          // divisor
);
```

## Threading Model

The audio service uses a small set of well-defined threads:

- **Main / EDT threads** — service lifecycle (`start()`, `stop()`), configuration
  (`setDivisor()`, `recomputeLayout()`), UI rendering (`WaveformPanel`, `SpectrumPanel`,
  `SpectrogramPanel`, `PhaseDiagramPanel`, `DiagnosisPanel`).
- **Capture worker thread** — daemon, single-thread executor. Runs the capture loop, decodes
  raw bytes into normalized `float[channels][frames]`, builds an immutable `AudioBlock`, and
  publishes it to downstream consumers.
- **DSP / analysis consumer threads** (optional) — single consumer per `AudioRingBuffer` (the
  buffer is strict SPSC); the application currently consumes "latest wins" on the EDT, but new
  pipelines can spawn their own consumer thread.

### Synchronization primitives in `AudioCaptureServiceImpl`

The current implementation deliberately avoids locks on the hot path. State is shared through a
small number of atomics / volatiles:

|                   Field                   |                                            Concurrency role                                            |
|-------------------------------------------|--------------------------------------------------------------------------------------------------------|
| `AtomicBoolean running`                   | Lifecycle flag. Set by `start()` / `stop()`, polled by the worker.                                     |
| `AudioRingBuffer<AudioBlock> ringBuffer`  | Lock-free SPSC queue. Worker calls `offer(block)`; consumer calls `poll()` / `drainTo(...)`.           |
| `volatile AudioBlock latestBlock`         | "Latest wins" pointer for cheap UI / REST consumers that don't drain the ring buffer.                  |
| `volatile WaveformModel latestModel`      | Legacy snapshot kept in sync for existing Swing panels.                                                |
| `volatile int panelWidth` / `panelHeight` | Layout hints from the UI; read in the worker to drive `WaveformRenderer`.                              |
| `volatile byte[] datas`, `int datasize`   | Worker-owned decode buffers; declared `volatile` so reconfiguration on another thread becomes visible. |

> **There is no `ReentrantLock modelLock`.** Earlier drafts of this page described one; that
> design was retired when the capture path moved to immutable `AudioBlock` publication. Mutable
> per-pixel state (`xPoints`, `yPoints`, ...) no longer lives in the capture service.

### Immutable snapshot publication

Snapshots are immutable and thread-safe:

- `AudioBlock` carries `float[channels][frames]` samples plus `frameIndex` and
  `timestampNanos`. Construction copies its inputs; accessors return views into the captured
  arrays which are not mutated after publication.
- `AnalysisSnapshot` implementations (`RmsPeakSnapshot`, `SpectrumSnapshot`,
  `StereoDelaySnapshot`, `DiagnosisSnapshot`, `SpectrogramFrame`, ...) are similarly
  immutable and safe to hand to UI panels or exporters from any thread.
- `WaveformModel` (legacy) is still produced by the worker for backwards-compatible Swing
  rendering; it is built from the same `AudioBlock` the rest of the platform sees.

This enables lock-free reads, non-blocking publishes, and consistent rendering snapshots (no
torn updates).

### Best practices when modifying the service

- Treat `AudioBlock` and analysis snapshots as immutable. Publish new instances; do not mutate
  existing ones.
- Use `ringBuffer.offer(...)` from the producer (drop on overflow). Do **not** call
  `offerOverwrite(...)` while a separate consumer thread is draining the same buffer — see the
  Javadoc on `AudioRingBuffer.offerOverwrite` for the SPSC restriction.
- Use the `latestBlock` volatile pointer for "give me the most recent block" UI consumers.
- Use `volatile` for simple state flags whose visibility matters and atomics for lock-free
  counters; avoid introducing locks on the capture path.

## Performance Notes

The capture pipeline is optimized for real-time processing:

- **Buffer reuse** — the `datas` byte array and the per-channel decode buffer are allocated
  once and reused across capture iterations; only the exact-sized `float[channels][frames]`
  used to construct the immutable `AudioBlock` is freshly allocated per iteration.
- **Precomputed constants** — `bytesPerSample` and `frameSize` are computed once when the
  audio line opens.
- **Lock-free publication** — the ring buffer uses `lazySet` on producer / consumer sequences
  and a power-of-two mask; "latest wins" UI reads go through the `volatile latestBlock`
  pointer instead of acquiring a lock.

These optimizations preserve external behavior while reducing GC churn and improving
throughput.

## Logging & Observability

The application uses `java.util.logging` for diagnostics.

|   Level   |                               Used for                                |
|-----------|-----------------------------------------------------------------------|
| `INFO`    | Lifecycle events (service start/stop, audio line opened).             |
| `FINE`    | Detailed diagnostics (data size computation, capture loop lifecycle). |
| `WARNING` | Recoverable errors (line already running, line close errors).         |
| `SEVERE`  | Critical failures (capture init failure, capture loop exceptions).    |

### Enabling FINE Logging

```bash
java -Djava.util.logging.config.file=logging.properties \
     -jar audio-app/target/audio-app-*.jar
```

`logging.properties`:

```properties
.level=INFO
org.hammer.audio.AudioCaptureServiceImpl.level=FINE
handlers=java.util.logging.ConsoleHandler
java.util.logging.ConsoleHandler.level=FINE
java.util.logging.ConsoleHandler.formatter=java.util.logging.SimpleFormatter
```

> Avoid enabling `FINE` in production; it generates substantial output during real-time capture.

