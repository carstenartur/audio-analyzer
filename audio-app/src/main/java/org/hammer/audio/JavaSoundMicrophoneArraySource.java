package org.hammer.audio;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.TargetDataLine;
import org.hammer.audio.acquisition.CaptureDeviceConfiguration;
import org.hammer.audio.acquisition.LocalizationInputMode;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.acquisition.MicrophoneArrayProfile;
import org.hammer.audio.acquisition.MicrophoneArrayReadiness;
import org.hammer.audio.acquisition.MultiChannelAudioSource;
import org.hammer.audio.capture.SampleDecoder;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;

/** Live JavaSound adapter for the common synchronized multichannel source contract. */
public final class JavaSoundMicrophoneArraySource implements MultiChannelAudioSource {

  private final MicrophoneArrayProfile profile;
  private final CaptureDeviceConfiguration configuration;
  private final SampleDecoder decoder;
  private final AudioLineProvider lineProvider;

  private TargetDataLine line;
  private long nextFrameIndex;
  private boolean closed;

  /**
   * Open a live source for the device selected in the profile.
   *
   * @param profile reusable live array profile
   * @param maximumTimingErrorSamples accepted one-sigma timing error budget
   */
  public JavaSoundMicrophoneArraySource(
      MicrophoneArrayProfile profile, double maximumTimingErrorSamples) {
    this(profile, provider(profile), maximumTimingErrorSamples, Instant.now());
  }

  JavaSoundMicrophoneArraySource(
      MicrophoneArrayProfile profile,
      AudioLineProvider lineProvider,
      double maximumTimingErrorSamples,
      Instant observationTime) {
    this.profile = Objects.requireNonNull(profile, "profile");
    this.lineProvider = Objects.requireNonNull(lineProvider, "lineProvider");
    MicrophoneArrayReadiness readiness =
        MicrophoneArrayReadiness.assess(
            profile,
            LocalizationInputMode.LIVE,
            Objects.requireNonNull(observationTime, "observationTime"),
            maximumTimingErrorSamples);
    if (!readiness.ready()) {
      throw new IllegalArgumentException(
          "microphone-array profile is not ready for live localization: "
              + String.join(" ", readiness.diagnostics()));
    }
    this.configuration = profile.liveCaptureConfiguration().orElseThrow();
    this.decoder =
        new SampleDecoder(
            configuration.format(), configuration.signed(), configuration.bigEndian());
  }

  @Override
  public AudioFormatDescriptor format() {
    return configuration.format();
  }

  @Override
  public MicrophoneArray microphoneArray() {
    return profile.array();
  }

  @Override
  public Optional<AudioBlock> readBlock(int frames) throws IOException {
    if (frames <= 0) {
      throw new IllegalArgumentException("frames must be > 0");
    }
    if (closed) {
      return Optional.empty();
    }
    ensureOpen();
    byte[] data = new byte[Math.multiplyExact(frames, decoder.frameSize())];
    int byteCount = readUpTo(data);
    int decodedFrames = decoder.framesIn(byteCount);
    if (decodedFrames == 0) {
      return Optional.empty();
    }
    float[][] samples = new float[format().channels()][decodedFrames];
    decoder.decode(data, decodedFrames * decoder.frameSize(), samples);
    AudioBlock block = AudioBlock.wrap(format(), samples, nextFrameIndex, System.nanoTime());
    nextFrameIndex += decodedFrames;
    return Optional.of(block);
  }

  @Override
  public void close() {
    closed = true;
    if (line == null) {
      return;
    }
    line.stop();
    line.flush();
    line.close();
    line = null;
  }

  private void ensureOpen() {
    if (line != null) {
      return;
    }
    AudioFormatDescriptor format = configuration.format();
    AudioFormat javaSoundFormat =
        new AudioFormat(
            format.sampleRate(),
            format.sourceSampleSizeInBits(),
            format.channels(),
            configuration.signed(),
            configuration.bigEndian());
    line = lineProvider.acquireLine(javaSoundFormat);
    line.start();
  }

  private int readUpTo(byte[] data) throws IOException {
    int total = 0;
    while (total < data.length && !closed) {
      int count = line.read(data, total, data.length - total);
      if (count < 0) {
        break;
      }
      if (count == 0) {
        return total;
      }
      total += count;
    }
    return total;
  }

  private static AudioLineProvider provider(MicrophoneArrayProfile profile) {
    Objects.requireNonNull(profile, "profile");
    String deviceId = profile.liveCaptureConfiguration().orElseThrow().device().deviceId();
    JavaSoundCaptureDeviceDiscovery discovery = new JavaSoundCaptureDeviceDiscovery();
    return new DefaultAudioLineProvider(
        discovery
            .findMixerInfo(deviceId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException("capture device is not available: " + deviceId)));
  }
}
