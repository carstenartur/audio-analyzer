package org.hammer.audio.experiment.operations;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.acquisition.MultiChannelAudioSource;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.hammer.audio.recording.AudioBlockRecordingReader;

/** Streaming, bounded-allocation replay adapter for the same multichannel localization runner. */
final class RecordingMicrophoneSource implements MultiChannelAudioSource {

  private final AudioBlockRecordingReader reader;
  private final MicrophoneArray array;
  private AudioBlock pending;
  private int offset;

  RecordingMicrophoneSource(Path file, MicrophoneArray array) throws IOException {
    reader = AudioBlockRecordingReader.open(file);
    this.array = array;
    if (reader.format().channels() != array.channels()) {
      reader.close();
      throw new IOException("Replay channel count does not match the selected microphone array.");
    }
  }

  @Override
  public AudioFormatDescriptor format() {
    return reader.format();
  }

  @Override
  public MicrophoneArray microphoneArray() {
    return array;
  }

  @Override
  public Optional<AudioBlock> readBlock(int frames) throws IOException {
    float[][] samples = new float[array.channels()][frames];
    int copied = 0;
    long firstFrame = 0L;
    long timestamp = 0L;
    while (copied < frames) {
      if (pending == null || offset == pending.frames()) {
        pending = reader.next().orElse(null);
        offset = 0;
      }
      if (pending == null) {
        break;
      }
      if (copied == 0) {
        firstFrame = pending.frameIndex() + offset;
        timestamp =
            pending.timestampNanos() + (long) (offset * 1_000_000_000.0 / format().sampleRate());
      }
      int count = Math.min(frames - copied, pending.frames() - offset);
      for (int channel = 0; channel < samples.length; channel++) {
        System.arraycopy(pending.channelView(channel), offset, samples[channel], copied, count);
      }
      offset += count;
      copied += count;
    }
    if (copied == 0) {
      return Optional.empty();
    }
    if (copied < frames) {
      for (int channel = 0; channel < samples.length; channel++) {
        samples[channel] = Arrays.copyOf(samples[channel], copied);
      }
    }
    return Optional.of(new AudioBlock(format(), samples, firstFrame, timestamp));
  }

  @Override
  public void close() throws IOException {
    reader.close();
  }
}
