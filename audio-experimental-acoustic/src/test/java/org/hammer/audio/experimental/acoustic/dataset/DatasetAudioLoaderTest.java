package org.hammer.audio.experimental.acoustic.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.hammer.audio.core.AudioBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatasetAudioLoaderTest {

  @TempDir Path tempDir;

  // -------------------------------------------------------------------------
  // inspect()
  // -------------------------------------------------------------------------

  @Test
  void inspectReturnsCorrectMetadataForMono16BitWav() throws IOException {
    Path file = tempDir.resolve("mono16.wav");
    writeSigned16(file, 8_000, 1, 0.5);

    DatasetAudioLoader.AudioFileInfo info = DatasetAudioLoader.inspect(file);

    assertEquals(8_000.0, info.sampleRateHz(), 1.0);
    assertEquals(0.5, info.durationSeconds(), 0.01);
    assertEquals(1, info.channelCount());
    assertEquals(16, info.sampleSizeBits());
  }

  @Test
  void inspectReturnsCorrectMetadataForStereo16BitWav() throws IOException {
    Path file = tempDir.resolve("stereo16.wav");
    writeSigned16(file, 16_000, 2, 1.0);

    DatasetAudioLoader.AudioFileInfo info = DatasetAudioLoader.inspect(file);

    assertEquals(16_000.0, info.sampleRateHz(), 1.0);
    assertEquals(1.0, info.durationSeconds(), 0.01);
    assertEquals(2, info.channelCount());
    assertEquals(16, info.sampleSizeBits());
  }

  // -------------------------------------------------------------------------
  // load()
  // -------------------------------------------------------------------------

  @Test
  void loadDecodesSignedMono16BitWavToSingleChannel() throws IOException {
    Path file = tempDir.resolve("load-mono16.wav");
    writeSigned16(file, 8_000, 1, 0.5);

    AudioBlock block = new DatasetAudioLoader().load(file);

    assertEquals(1, block.format().channels());
    assertEquals(8_000.0f, block.format().sampleRate(), 1.0f);
    assertTrue(block.samples()[0].length > 0, "samples should not be empty");
  }

  @Test
  void loadDecodesSignedStereo16BitWavToTwoChannels() throws IOException {
    Path file = tempDir.resolve("load-stereo16.wav");
    writeSigned16(file, 16_000, 2, 1.0);

    AudioBlock block = new DatasetAudioLoader().load(file);

    assertEquals(2, block.format().channels());
    assertEquals(16_000.0f, block.format().sampleRate(), 1.0f);
  }

  @Test
  void loadDecodesUnsigned8BitMonoWav() throws IOException {
    Path file = tempDir.resolve("unsigned8.wav");
    writeUnsigned8(file, 8_000, 1, 0.5);

    AudioBlock block = new DatasetAudioLoader().load(file);

    assertEquals(1, block.format().channels());
    assertEquals(8_000.0f, block.format().sampleRate(), 1.0f);
    assertTrue(block.samples()[0].length > 0, "samples should not be empty");
  }

  @Test
  void loadDecodesUnsigned8BitStereoWav() throws IOException {
    Path file = tempDir.resolve("unsigned8-stereo.wav");
    writeUnsigned8(file, 11_025, 2, 0.5);

    AudioBlock block = new DatasetAudioLoader().load(file);

    assertEquals(2, block.format().channels());
  }

  @Test
  void loadNormalizesSamplesWithinMinusOneToOne() throws IOException {
    Path file = tempDir.resolve("norm16.wav");
    writeSigned16(file, 8_000, 1, 0.5);

    AudioBlock block = new DatasetAudioLoader().load(file);

    float[] samples = block.samples()[0];
    for (float s : samples) {
      assertTrue(s >= -1.0f && s <= 1.0f, "sample " + s + " out of [-1, 1]");
    }
  }

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  private static void writeSigned16(Path file, int sampleRate, int channels, double seconds)
      throws IOException {
    int frames = (int) Math.round(sampleRate * seconds);
    int bytesPerFrame = 2 * channels;
    byte[] pcm = new byte[frames * bytesPerFrame];
    for (int i = 0; i < frames; i++) {
      short sample =
          (short)
              Math.round(Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 0.5 * Short.MAX_VALUE);
      for (int ch = 0; ch < channels; ch++) {
        int off = i * bytesPerFrame + ch * 2;
        pcm[off] = (byte) (sample & 0xFF);
        pcm[off + 1] = (byte) ((sample >>> 8) & 0xFF);
      }
    }
    AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
    try (AudioInputStream stream =
        new AudioInputStream(new ByteArrayInputStream(pcm), format, frames)) {
      AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file.toFile());
    }
  }

  private static void writeUnsigned8(Path file, int sampleRate, int channels, double seconds)
      throws IOException {
    int frames = (int) Math.round(sampleRate * seconds);
    byte[] pcm = new byte[frames * channels];
    for (int i = 0; i < frames; i++) {
      int sample = (int) Math.round(Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 100 + 128);
      sample = Math.max(0, Math.min(255, sample));
      for (int ch = 0; ch < channels; ch++) {
        pcm[i * channels + ch] = (byte) sample;
      }
    }
    AudioFormat format = new AudioFormat(sampleRate, 8, channels, false, false);
    try (AudioInputStream stream =
        new AudioInputStream(new ByteArrayInputStream(pcm), format, frames)) {
      AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file.toFile());
    }
  }
}
