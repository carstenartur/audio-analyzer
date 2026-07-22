package org.hammer.audio;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import org.hammer.audio.buffer.AudioRingBuffer;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.hammer.audio.signal.ChirpGenerator;
import org.hammer.audio.signal.DemoPresetGenerator;
import org.hammer.audio.signal.SignalGenerator;
import org.hammer.audio.signal.SineGenerator;
import org.hammer.audio.signal.SquareGenerator;
import org.hammer.audio.snapshot.WaveformSnapshot;
import org.hammer.audio.ui.WaveformRenderer;

/**
 * Deterministic {@link AudioCaptureService} based on synthetic signal generators.
 *
 * <p>Used for UI demos and testing in environments without a microphone.
 */
public final class DemoAudioCaptureService implements AudioCaptureService {

  private static final int RING_BUFFER_CAPACITY = 64;
  private static final int DEFAULT_FRAMES_PER_BLOCK = 1024;
  private static final int MIN_FRAMES_PER_BLOCK = 64;
  private static final float DEMO_AMPLITUDE = 0.75f;
  private static final float TICK_SECONDS = 1f / 1000f;

  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AudioFormatDescriptor descriptor;
  private final SignalGenerator signalGenerator;
  private final AudioRingBuffer<AudioBlock> ringBuffer =
      new AudioRingBuffer<>(RING_BUFFER_CAPACITY);
  private final AudioBlockBroadcaster broadcaster = new AudioBlockBroadcaster();
  private final int tickEveryNSamples;
  private final AudioFormat format;

  private volatile WaveformModel latestModel = WaveformModel.EMPTY;
  private volatile AudioBlock latestBlock;
  private volatile int divisor;
  private volatile int panelWidth = 640;
  private volatile int panelHeight = 200;

  private ExecutorService workerExecutor;

  public DemoAudioCaptureService(
      float sampleRate,
      int sampleSizeInBits,
      int channels,
      int divisor,
      DemoSignalType signalType) {
    if (divisor < 1) {
      throw new IllegalArgumentException("Divisor must be >= 1");
    }
    this.descriptor =
        new AudioFormatDescriptor(sampleRate, Math.max(1, channels), sampleSizeInBits);
    this.signalGenerator = createSignalGenerator(descriptor, signalType);
    this.divisor = divisor;
    this.tickEveryNSamples = (int) (sampleRate * TICK_SECONDS);
    this.format = new AudioFormat(sampleRate, sampleSizeInBits, descriptor.channels(), true, false);
  }

  @Override
  public void start() {
    if (running.getAndSet(true)) {
      return;
    }
    workerExecutor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread worker = new Thread(runnable, "DemoAudioCaptureWorker");
              worker.setDaemon(true);
              return worker;
            });
    workerExecutor.submit(this::generateLoop);
    ActiveAudioCaptureRegistry.activate(this);
  }

  @Override
  public void stop() {
    if (!running.getAndSet(false)) {
      return;
    }
    ActiveAudioCaptureRegistry.deactivate(this);
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
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  @Override
  public WaveformModel getLatestModel() {
    return latestModel != null ? latestModel : WaveformModel.EMPTY;
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
  }

  @Override
  public int getDivisor() {
    return divisor;
  }

  @Override
  public void recomputeLayout(int width, int height) {
    panelWidth = width;
    panelHeight = height;
    AudioBlock block = latestBlock;
    if (block != null) {
      latestModel = buildLegacyModel(block);
    }
  }

  private void generateLoop() {
    while (running.get() && !Thread.currentThread().isInterrupted()) {
      int frames = Math.max(MIN_FRAMES_PER_BLOCK, DEFAULT_FRAMES_PER_BLOCK / Math.max(1, divisor));
      AudioBlock block = signalGenerator.nextBlock(frames);
      latestBlock = block;
      ringBuffer.offer(block);
      broadcaster.publish(block);
      latestModel = buildLegacyModel(block);
      int sleepMillis =
          Math.max(10, Math.round((1000f * frames) / Math.max(1f, descriptor.sampleRate())));
      try {
        Thread.sleep(sleepMillis);
      } catch (InterruptedException interruptedException) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private WaveformModel buildLegacyModel(AudioBlock block) {
    WaveformSnapshot snapshot =
        WaveformSnapshot.wrap(
            block.samples(),
            block.format().sampleRate(),
            block.frameIndex(),
            block.timestampNanos());
    int[] xPoints = WaveformRenderer.computeXPoints(snapshot.frames(), panelWidth);
    int[][] yPoints;
    if (panelHeight <= 0) {
      yPoints = new int[snapshot.channels()][0];
    } else {
      yPoints = WaveformRenderer.computeYPointsAllChannels(snapshot, panelHeight);
    }
    int dataSizeBytes =
        snapshot.frames()
            * snapshot.channels()
            * Math.max(1, descriptor.sourceSampleSizeInBits() / 8);
    return new WaveformModel(xPoints, yPoints, tickEveryNSamples, dataSizeBytes);
  }

  private static SignalGenerator createSignalGenerator(
      AudioFormatDescriptor format, DemoSignalType signalType) {
    DemoSignalType selected = signalType == null ? DemoSignalType.SINE : signalType;
    return switch (selected) {
      case SINE -> new SineGenerator(format, 440.0, DEMO_AMPLITUDE);
      case SQUARE -> new SquareGenerator(format, 440.0, DEMO_AMPLITUDE);
      case CHIRP -> {
        ChirpGenerator chirpGenerator =
            new ChirpGenerator(format, 120.0, 2800.0, 2.5, DEMO_AMPLITUDE);
        chirpGenerator.setLooping(true);
        yield chirpGenerator;
      }
      case MOSQUITO_BURST, MOVING_CHIRP, HUM_HARMONICS, CLIPPING_TEST, STEREO_DELAY_TEST ->
          new DemoPresetGenerator(format, selected);
    };
  }
}
