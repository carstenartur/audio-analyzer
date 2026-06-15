package org.hammer.audio.experimental.acoustic.dataset;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.hammer.audio.capture.SampleDecoder;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;

/** Loads local dataset audio files into normalized {@link AudioBlock} instances. */
public final class DatasetAudioLoader {

  /**
   * Inspect one audio file without exposing the decoded samples.
   *
   * @param audioPath local audio file
   * @return basic audio metadata
   * @throws IOException when the audio file cannot be opened
   */
  public static AudioFileInfo inspect(Path audioPath) throws IOException {
    Objects.requireNonNull(audioPath, "audioPath");
    try (AudioInputStream stream = AudioSystem.getAudioInputStream(audioPath.toFile())) {
      AudioFormat format = stream.getFormat();
      double sampleRate = format.getSampleRate();
      long frameLength = stream.getFrameLength();
      double duration = frameLength > 0 && sampleRate > 0.0 ? frameLength / sampleRate : 0.0;
      return new AudioFileInfo(
          sampleRate, duration, format.getChannels(), format.getSampleSizeInBits());
    } catch (UnsupportedAudioFileException ex) {
      throw new IOException("Unsupported audio file: " + audioPath, ex);
    }
  }

  /**
   * Decode one audio file into a normalized {@link AudioBlock}.
   *
   * @param audioPath local audio file
   * @return decoded audio block
   * @throws IOException when the audio file cannot be decoded
   */
  public AudioBlock load(Path audioPath) throws IOException {
    Objects.requireNonNull(audioPath, "audioPath");
    try (AudioInputStream sourceStream = AudioSystem.getAudioInputStream(audioPath.toFile())) {
      AudioFormat baseFormat = sourceStream.getFormat();
      AudioFormat targetFormat = pcmFormat(baseFormat);
      try (AudioInputStream pcmStream =
          needsConversion(baseFormat)
              ? AudioSystem.getAudioInputStream(targetFormat, sourceStream)
              : sourceStream) {
        AudioFormat effectiveFormat = pcmStream.getFormat();
        AudioFormatDescriptor descriptor =
            new AudioFormatDescriptor(
                effectiveFormat.getSampleRate(),
                effectiveFormat.getChannels(),
                effectiveFormat.getSampleSizeInBits());
        SampleDecoder decoder =
            new SampleDecoder(
                descriptor,
                AudioFormat.Encoding.PCM_SIGNED.equals(effectiveFormat.getEncoding()),
                effectiveFormat.isBigEndian());
        byte[] bytes = pcmStream.readAllBytes();
        int frames = decoder.framesIn(bytes.length);
        float[][] samples = new float[descriptor.channels()][frames];
        decoder.decode(bytes, bytes.length, samples);
        return new AudioBlock(descriptor, samples, 0L, 0L);
      }
    } catch (UnsupportedAudioFileException ex) {
      throw new IOException("Unsupported audio file: " + audioPath, ex);
    }
  }

  private static boolean needsConversion(AudioFormat format) {
    AudioFormat.Encoding encoding = format.getEncoding();
    return !AudioFormat.Encoding.PCM_SIGNED.equals(encoding)
        && !AudioFormat.Encoding.PCM_UNSIGNED.equals(encoding);
  }

  private static AudioFormat pcmFormat(AudioFormat format) {
    if (!needsConversion(format)) {
      return format;
    }
    int channels = Math.max(1, format.getChannels());
    return new AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        format.getSampleRate(),
        16,
        channels,
        channels * 2,
        format.getSampleRate(),
        false);
  }

  /**
   * Basic metadata for one inspected audio file.
   *
   * @param sampleRateHz audio sample rate in hertz
   * @param durationSeconds decoded or header-derived duration in seconds
   * @param channelCount number of audio channels
   * @param sampleSizeBits source sample size in bits
   */
  public record AudioFileInfo(
      double sampleRateHz, double durationSeconds, int channelCount, int sampleSizeBits) {

    public AudioFileInfo {
      if (!Double.isFinite(sampleRateHz) || sampleRateHz <= 0.0) {
        throw new IllegalArgumentException("sampleRateHz must be finite and > 0");
      }
      if (!Double.isFinite(durationSeconds) || durationSeconds < 0.0) {
        throw new IllegalArgumentException("durationSeconds must be finite and >= 0");
      }
      if (channelCount < 1) {
        throw new IllegalArgumentException("channelCount must be >= 1");
      }
      if (sampleSizeBits < 1) {
        throw new IllegalArgumentException("sampleSizeBits must be >= 1");
      }
    }
  }
}
