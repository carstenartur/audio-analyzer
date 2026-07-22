package org.hammer.audio.recording;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioBlockRecordingRoundTripTest {

  private static final AudioFormatDescriptor STEREO_44K = new AudioFormatDescriptor(44100f, 2, 16);

  @Test
  void roundTripsFinalizedV2WithSamplesCountersAndDigest(@TempDir Path directory)
      throws IOException {
    Path file = directory.resolve("recording.aarec");
    AudioBlock first = block(10L, 1_000_000_000L, new float[] {0.1f, 0.2f, 0.3f});
    AudioBlock second = block(13L, 2_000_000_000L, new float[] {0.4f, 0.5f});

    try (AudioBlockRecordingWriter writer = AudioBlockRecordingWriter.open(file)) {
      writer.write(first);
      writer.write(second);
      assertEquals(2L, writer.blocksWritten());
      assertEquals(5L, writer.totalFrames());
      assertEquals(0L, writer.continuityGapCount());
      assertFalse(Files.exists(file));
      assertTrue(Files.exists(writer.partialFile()));
    }

    List<AudioBlock> read = AudioBlockRecordingReader.readAll(file);
    RecordingInspection inspection = AudioBlockRecordingReader.inspect(file);

    assertEquals(2, read.size());
    assertEquals(STEREO_44K, read.get(0).format());
    assertEquals(10L, read.get(0).frameIndex());
    assertArrayEquals(new float[] {0.1f, 0.2f, 0.3f}, read.get(0).channelView(0), 1.0e-6f);
    assertEquals(RecordingIntegrity.COMPLETE, inspection.integrity());
    assertEquals(AudioBlockRecordingFormat.VERSION, inspection.formatVersion());
    assertEquals(2L, inspection.blockCount());
    assertEquals(5L, inspection.totalFrames());
    assertEquals(64, inspection.sha256().length());
  }

  @Test
  void importsLegacyV1ButMarksItUnverified(@TempDir Path directory) throws IOException {
    Path file = directory.resolve("legacy.aar");
    writeLegacyV1(file, block(0L, 123L, new float[] {0.25f, 0.5f}));

    List<AudioBlock> blocks = AudioBlockRecordingReader.readAll(file);
    RecordingInspection inspection = AudioBlockRecordingReader.inspect(file);

    assertEquals(1, blocks.size());
    assertEquals(RecordingIntegrity.LEGACY_UNVERIFIED, inspection.integrity());
    assertEquals(AudioBlockRecordingFormat.LEGACY_VERSION, inspection.formatVersion());
  }

  @Test
  void missingFooterIsRejectedStrictlyButRecoverableWithoutChangingSource(@TempDir Path directory)
      throws IOException {
    Path target = directory.resolve("interrupted.aarec");
    Path partial;
    AudioBlockRecordingWriter writer = AudioBlockRecordingWriter.open(target);
    writer.write(block(0L, 100L, new float[] {0.1f, 0.2f}));
    partial = writer.partialFile();
    writer.abort();

    assertFalse(Files.exists(target));
    assertThrows(IOException.class, () -> AudioBlockRecordingReader.readAll(partial));
    RecordingInspection partialInspection = AudioBlockRecordingReader.inspect(partial);
    assertEquals(RecordingIntegrity.RECOVERABLE_INCOMPLETE, partialInspection.integrity());
    assertEquals(1L, partialInspection.blockCount());

    byte[] original = Files.readAllBytes(partial);
    Path recovered = directory.resolve("recovered.aarec");
    RecordingInspection recoveredInspection = AudioBlockRecordingReader.recover(partial, recovered);

    assertEquals(RecordingIntegrity.COMPLETE, recoveredInspection.integrity());
    assertArrayEquals(original, Files.readAllBytes(partial));
    assertEquals(1, AudioBlockRecordingReader.readAll(recovered).size());
  }

  @Test
  void readerRejectsBadMagic(@TempDir Path directory) throws IOException {
    Path file = directory.resolve("garbage.aarec");
    Files.write(file, new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15});
    IOException exception =
        assertThrows(IOException.class, () -> AudioBlockRecordingReader.readAll(file));
    assertTrue(exception.getMessage().contains("header"));
  }

  @Test
  void writerRejectsFormatChange(@TempDir Path directory) throws IOException {
    Path file = directory.resolve("change.aarec");
    AudioBlock first = block(0L, 0L, new float[] {0f});
    AudioBlock second =
        new AudioBlock(
            new AudioFormatDescriptor(48000f, 2, 16),
            new float[][] {{0f}, {0f}},
            1L,
            0L);
    try (AudioBlockRecordingWriter writer = AudioBlockRecordingWriter.open(file)) {
      writer.write(first);
      assertThrows(IllegalStateException.class, () -> writer.write(second));
    }
  }

  private static AudioBlock block(long frameIndex, long timestamp, float[] channel) {
    float[] second = new float[channel.length];
    for (int index = 0; index < channel.length; index++) {
      second[index] = -channel[index];
    }
    return new AudioBlock(STEREO_44K, new float[][] {channel, second}, frameIndex, timestamp);
  }

  private static void writeLegacyV1(Path file, AudioBlock block) throws IOException {
    try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(file))) {
      output.writeInt(AudioBlockRecordingFormat.LEGACY_MAGIC);
      output.writeShort(AudioBlockRecordingFormat.LEGACY_VERSION);
      output.writeShort(block.channels());
      output.writeFloat(block.format().sampleRate());
      output.writeShort(block.format().sourceSampleSizeInBits());
      output.writeShort(0);
      output.writeInt(block.frames());
      output.writeLong(block.frameIndex());
      output.writeLong(block.timestampNanos());
      for (int channel = 0; channel < block.channels(); channel++) {
        for (float sample : block.channelView(channel)) {
          output.writeFloat(sample);
        }
      }
    }
  }
}
