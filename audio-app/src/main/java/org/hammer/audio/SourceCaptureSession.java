package org.hammer.audio;

import java.io.IOException;
import java.util.Optional;
import javax.sound.sampled.AudioFormat;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.acquisition.MultiChannelAudioSource;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;

/** Bridges the shared pull source to the existing loss-aware recorder subscription. */
public final class SourceCaptureSession implements MultiChannelAudioSource, AudioCaptureService {

  private final MultiChannelAudioSource source;
  private final AudioBlockBroadcaster broadcaster = new AudioBlockBroadcaster();
  private final long frameLimit;
  private final int blockFrames;
  private volatile boolean running = true;
  private volatile AudioBlock latest;
  private volatile long consumedFrames;

  /** Create a bounded capture session; no audio is consumed until the shared runner reads. */
  public SourceCaptureSession(MultiChannelAudioSource source, long frameLimit) {
    this(source, frameLimit, 1);
  }

  /** Create a session with the runner's configured block size for exact recording preflight. */
  public SourceCaptureSession(MultiChannelAudioSource source, long frameLimit, int blockFrames) {
    this.source = java.util.Objects.requireNonNull(source, "source");
    if (frameLimit < 1 || blockFrames < 1) {
      throw new IllegalArgumentException("Frame limit and block size must be positive.");
    }
    this.frameLimit = frameLimit;
    this.blockFrames = blockFrames;
  }

  @Override
  public AudioFormatDescriptor format() {
    return source.format();
  }

  @Override
  public MicrophoneArray microphoneArray() {
    return source.microphoneArray();
  }

  @Override
  public Optional<AudioBlock> readBlock(int frames) throws IOException {
    if (!running || consumedFrames >= frameLimit) {
      return Optional.empty();
    }
    Optional<AudioBlock> block =
        source.readBlock((int) Math.min(frames, frameLimit - consumedFrames));
    if (block.isPresent()) {
      latest = block.get();
      consumedFrames += latest.frames();
      broadcaster.publish(latest);
    }
    return block;
  }

  /** Frames actually consumed from this source, including a final partial block. */
  public long framesRead() {
    return consumedFrames;
  }

  @Override
  public void close() throws IOException {
    running = false;
    source.close();
  }

  @Override
  public void start() {
    running = true;
  }

  @Override
  public void stop() {
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public WaveformModel getLatestModel() {
    return WaveformModel.EMPTY;
  }

  @Override
  public AudioFormat getFormat() {
    return new AudioFormat(
        format().sampleRate(), format().sourceSampleSizeInBits(), format().channels(), true, false);
  }

  @Override
  public AudioFormatDescriptor getDescriptor() {
    return format();
  }

  @Override
  public AudioBlock getLatestBlock() {
    return latest;
  }

  @Override
  public int getExpectedBlockFrames() {
    return blockFrames;
  }

  @Override
  public AudioBlockSubscription subscribe(AudioBlockListener listener) {
    return broadcaster.subscribe(listener);
  }

  @Override
  public void setDivisor(int divisor) {
    /* The shared runner owns block sizing. */
  }

  @Override
  public int getDivisor() {
    return 1;
  }

  @Override
  public void recomputeLayout(int width, int height) {
    /* No waveform projection. */
  }
}
