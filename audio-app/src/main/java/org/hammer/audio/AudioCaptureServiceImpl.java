package org.hammer.audio;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import org.hammer.audio.buffer.AudioRingBuffer;
import org.hammer.audio.capture.SampleDecoder;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.hammer.audio.snapshot.WaveformSnapshot;
import org.hammer.audio.ui.WaveformRenderer;

/**
 * Audio capture service implementation: the bridge between the JavaSound input device and the
 * platform's audio-domain pipeline.
 *
 * <p><strong>Architecture (post-refactor)</strong>:
 *
 * <pre>{@code
 * TargetDataLine
 *   -> raw bytes
 *   -> SampleDecoder (-> normalized float[][])
 *   -> AudioBlock (immutable, with frame index + timestamp)
 *   -> AudioRingBuffer<AudioBlock>  (lock-free SPSC; downstream DSP/analysis polls asynchronously)
 *   -> AudioBlockListener fan-out   (complete stream; callbacks only enqueue)
 *   -> latestBlock (volatile, for "give me the latest" UI consumers)
 *   -> WaveformModel (legacy compatibility view, built via WaveformRenderer)
 * }</pre>
 *
 * <p>The capture loop knows nothing about pixels, panel coordinates or Swing — pixel scaling has
 * moved into {@link WaveformRenderer}. The legacy {@link WaveformModel} is still produced for
 * existing UI consumers and tests; it is now derived from the same {@link AudioBlock} the rest of
 * the platform sees.
 *
 * <p>Thread-safety: all public methods are thread-safe. The capture worker thread is the sole
 * producer for the ring buffer; downstream DSP/analysis threads are the consumers.
 *
 * @author refactoring
 */
public class AudioCaptureServiceImpl implements AudioCaptureService {

  private static final Logger LOGGER = Logger.getLogger(AudioCaptureServiceImpl.class.getName());

  /** Tick distance in seconds (1 ms). */
  private static final float TICK_SECONDS = 1f / 1000f;

  /** Minimum buffer size in bytes to prevent overly small allocations. */
  private static final int MIN_BUFFER_SIZE = 256;

  /** Default ring-buffer capacity (in {@link AudioBlock}s). */
  private static final int RING_BUFFER_CAPACITY = 64;

  private final AtomicBoolean running = new AtomicBoolean(false);

  private volatile WaveformModel latestModel;
  private volatile AudioBlock latestBlock;

  // Audio configuration
  private final float sampleRate;
  private final int sampleSizeInBits;
  private final int channels;
  private final boolean signed;
  private final boolean bigEndian;
  private final AudioFormatDescriptor descriptor;
  private final SampleDecoder decoder;
  private final AudioRingBuffer<AudioBlock> ringBuffer;
  private final AudioBlockBroadcaster broadcaster = new AudioBlockBroadcaster();

  // Capture state
  private volatile int divisor;
  private volatile int panelWidth;
  private volatile int panelHeight;

  private TargetDataLine line;
  private AudioFormat format;
  private ExecutorService workerExecutor;

  // Capture buffers (mostly worker-thread owned; volatile for visibility on reconfiguration)
  private volatile byte[] datas;
  private volatile int datasize;
  private volatile int numberOfPoints;
  private final int tickEveryNSample;

  // Audio line provider (for testability)
  private final AudioLineProvider lineProvider;

  /** Create a new AudioCaptureServiceImpl with specified audio parameters. */
  public AudioCaptureServiceImpl(
      float sampleRate,
      int sampleSizeInBits,
      int channels,
      boolean signed,
      boolean bigEndian,
      int divisor) {
    this(
        sampleRate,
        sampleSizeInBits,
        channels,
        signed,
        bigEndian,
        divisor,
        new DefaultAudioLineProvider());
  }

  /** Create a new AudioCaptureServiceImpl using a selected JavaSound input mixer. */
  public AudioCaptureServiceImpl(
      float sampleRate,
      int sampleSizeInBits,
      int channels,
      boolean signed,
      boolean bigEndian,
      int divisor,
      Mixer.Info mixerInfo) {
    this(
        sampleRate,
        sampleSizeInBits,
        channels,
        signed,
        bigEndian,
        divisor,
        new DefaultAudioLineProvider(mixerInfo));
  }

  /** Package-private constructor for testing with custom AudioLineProvider. */
  AudioCaptureServiceImpl(
      float sampleRate,
      int sampleSizeInBits,
      int channels,
      boolean signed,
      boolean bigEndian,
      int divisor,
      AudioLineProvider lineProvider) {
    this.sampleRate = sampleRate;
    this.sampleSizeInBits = sampleSizeInBits;
    this.channels = Math.max(1, channels);
    this.signed = signed;
    this.bigEndian = bigEndian;
    this.divisor = Math.max(1, divisor);
    this.tickEveryNSample = (int) (TICK_SECONDS * sampleRate);
    this.panelWidth = 640;
    this.panelHeight = 200;
    this.lineProvider = lineProvider;
    this.descriptor = new AudioFormatDescriptor(sampleRate, this.channels, sampleSizeInBits);
    this.decoder = new SampleDecoder(descriptor, signed, bigEndian);
    this.ringBuffer = new AudioRingBuffer<>(RING_BUFFER_CAPACITY);
  }

  @Override
  public void start() {
    if (running.get()) {
      LOGGER.warning("AudioCaptureService is already running");
      return;
    }
    try {
      initializeAudioLine();
      computeDataSize();
      running.set(true);
      workerExecutor =
          Executors.newSingleThreadExecutor(
              runnable -> {
                Thread thread = new Thread(runnable, "AudioCaptureWorker");
                thread.setDaemon(true);
                return thread;
              });
      workerExecutor.submit(this::captureLoop);
      LOGGER.info("AudioCaptureService started successfully");
    } catch (Exception exception) {
      running.set(false);
      LOGGER.log(Level.SEVERE, "Failed to start AudioCaptureService", exception);
      throw new IllegalStateException("Failed to start audio capture", exception);
    }
  }

  @Override
  public void stop() {
    if (!running.get()) {
      return;
    }
    running.set(false);
    if (workerExecutor != null) {
      workerExecutor.shutdownNow();
      try {
        if (!workerExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
          workerExecutor.shutdownNow();
        }
      } catch (InterruptedException interruptedException) {
        Thread.currentThread().interrupt();
      }
      workerExecutor = null;
    }
    if (line != null) {
      try {
        line.stop();
        line.flush();
        line.close();
      } catch (Exception exception) {
        LOGGER.log(Level.WARNING, "Error closing TargetDataLine", exception);
      }
      line = null;
    }
    LOGGER.info("AudioCaptureService stopped");
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  @Override
  public WaveformModel getLatestModel() {
    WaveformModel cached = latestModel;
    return cached != null ? cached : WaveformModel.EMPTY;
  }

  @Override
  public AudioFormat getFormat() {
    return format;
  }

  @Override
  public AudioFormatDescriptor getDescriptor() {
    return descriptor;
  }

  @Override
  public AudioBlock getLatestBlock() {
    return latestBlock;
  }

  @Override
  public AudioBlockSubscription subscribe(AudioBlockListener listener) {
    return broadcaster.subscribe(listener);
  }

  @Override
  public AudioRingBuffer<AudioBlock> getRingBuffer() {
    return ringBuffer;
  }

  @Override
  public void setDivisor(int divisor) {
    if (divisor < 1) {
      throw new IllegalArgumentException("Divisor must be >= 1");
    }
    this.divisor = divisor;
    if (line != null) {
      computeDataSize();
    }
  }

  @Override
  public int getDivisor() {
    return divisor;
  }

  @Override
  public void recomputeLayout(int width, int height) {
    this.panelWidth = width;
    this.panelHeight = height;
    AudioBlock cached = latestBlock;
    if (cached != null) {
      latestModel = buildLegacyModel(cached);
    }
  }

  private void initializeAudioLine() {
    format = new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);
    line = lineProvider.acquireLine(format);
    LOGGER.info("Opened audio line with format: " + format);
  }

  private void computeDataSize() {
    if (line == null) {
      throw new IllegalStateException("Line must be opened before computing buffer sizes.");
    }
    datasize = Math.max(MIN_BUFFER_SIZE, line.getBufferSize() / Math.max(1, divisor));
    int frameSize = decoder.frameSize();
    int points = datasize / Math.max(1, frameSize);
    if (points <= 0) {
      points = 1;
    }
    numberOfPoints = points;
    datas = new byte[datasize];
    LOGGER.fine(String.format("Computed data size: %d, points: %d", datasize, points));
  }

  private void captureLoop() {
    if (line == null) {
      LOGGER.warning("TargetDataLine is null, aborting capture loop.");
      return;
    }
    line.start();

    long frameIndex = 0L;
    int allocatedFrames = numberOfPoints;
    float[][] decodeBuffer = new float[channels][allocatedFrames];

    while (running.get() && !Thread.currentThread().isInterrupted()) {
      try {
        byte[] localData = datas;
        int numBytesRead = line.read(localData, 0, localData.length);
        if (numBytesRead <= 0) {
          continue;
        }
        int currentPoints = numberOfPoints;
        if (allocatedFrames < currentPoints) {
          allocatedFrames = currentPoints;
          decodeBuffer = new float[channels][allocatedFrames];
        }

        int decodedFrames = Math.min(currentPoints, decoder.framesIn(numBytesRead));
        if (decodedFrames <= 0) {
          continue;
        }
        decoder.decode(localData, decodedFrames * decoder.frameSize(), decodeBuffer);
        for (int channel = 0; channel < channels; channel++) {
          for (int frame = decodedFrames; frame < currentPoints; frame++) {
            decodeBuffer[channel][frame] = 0f;
          }
        }

        float[][] blockSamples = new float[channels][currentPoints];
        for (int channel = 0; channel < channels; channel++) {
          System.arraycopy(decodeBuffer[channel], 0, blockSamples[channel], 0, currentPoints);
        }
        AudioBlock block = AudioBlock.wrap(descriptor, blockSamples, frameIndex, System.nanoTime());
        frameIndex += decodedFrames;

        ringBuffer.offer(block);
        broadcaster.publish(block);
        latestBlock = block;
        latestModel = buildLegacyModel(block);
      } catch (Exception exception) {
        if (running.get()) {
          LOGGER.log(Level.SEVERE, "Error during audio capture loop", exception);
        }
      }
    }
    LOGGER.fine("Capture loop ended");
  }

  private WaveformModel buildLegacyModel(AudioBlock block) {
    WaveformSnapshot snapshot =
        WaveformSnapshot.wrap(
            block.samples(),
            block.format().sampleRate(),
            block.frameIndex(),
            block.timestampNanos());
    int[] xPoints = WaveformRenderer.computeXPoints(snapshot.frames(), panelWidth);
    int height = panelHeight;
    int[][] yPoints;
    if (height <= 0) {
      yPoints = new int[snapshot.channels()][0];
    } else {
      yPoints = WaveformRenderer.computeYPointsAllChannels(snapshot, height);
    }
    return new WaveformModel(xPoints, yPoints, tickEveryNSample, datasize);
  }
}
