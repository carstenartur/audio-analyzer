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
import java.util.Arrays;
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
    AudioBlockRecordingWriter writer = AudioBlockRecordingWriter.open(target);
    writer.write(block(0L, 100L, new float[] {0.1f, 0.2f}));
    Path partial = writer.partialFile();
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
  void truncatedFooterIsClassifiedAndEarlierBlocksCanBeRecovered(@TempDir Path directory)
      throws IOException {
    Path complete = directory.resolve("complete.aarec");
    try (AudioBlockRecordingWriter writer = AudioBlockRecordingWriter.open(complete)) {
      writer.write(block(0L, 100L, new float[] {0.1f, 0.2f}));
      writer.write(block(2L, 200L, new float[] {0.3f, 0.4f}));
    }
    byte[] bytes = Files.readAllBytes(complete);
    Path truncated = directory.resolve("truncated.aarec.partial");
    Files.write(truncated, Arrays.copyOf(bytes, bytes.length - 12));

    assertThrows(IOException.class, () -> AudioBlockRecordingReader.readAll(truncated));
    RecordingInspection inspection = AudioBlockRecordingReader.inspect(truncated);
    assertEquals(RecordingIntegrity.TRUNCATED, inspection.integrity());
    assertEquals(2L, inspection.blockCount());

    Path recovered = directory.resolve("truncated-recovered.aarec");
    RecordingInspection recoveredInspection =
        AudioBlockRecordingReader.recover(truncated, recovered);
    assertEquals(RecordingIntegrity.COMPLETE, recoveredInspection.integrity());
    assertEquals(2L, recoveredInspection.blockCount());
  }

  @Test
  void legacyFileEndingInsideBlockIsTruncatedNotMerelyUnverified(@TempDir Path directory)
      throws IOException {
    Path complete = directory.resolve("legacy-complete.aar");
    writeLegacyV1(complete, block(0L, 123L, new float[] {0.25f, 0.5f}));
    byte[] bytes = Files.readAllBytes(complete);
    Path truncated = directory.resolve("legacy-truncated.aar");
    Files.write(truncated, Arrays.copyOf(bytes, bytes.length - 2));

    RecordingInspection inspection = AudioBlockRecordingReader.inspect(truncated);

    assertEquals(RecordingIntegrity.TRUNCATED, inspection.integrity());
    assertEquals(0L, inspection.blockCount());
    assertThrows(IOException.class, () -> AudioBlockRecordingReader.readAll(truncated));
  }

  @Test
  void checksumManipulationIsCorrupt(@TempDir Path directory) throws IOException {
    Path complete = directory.resolve("checksum.aarec");
    try (AudioBlockRecordingWriter writer = AudioBlockRecordingWriter.open(complete)) {
      writer.write(block(0L, 100L, new float[] {0.1f, 0.2f}));
    }
    byte[] bytes = Files.readAllBytes(complete);
    bytes[AudioBlockRecordingFormat.HEADER_BYTES + 20] ^= 0x01;
    Path corrupt = directory.resolve("checksum-corrupt.aarec");
    Files.write(corrupt, bytes);

    RecordingInspection inspection = AudioBlockRecordingReader.inspect(corrupt);

    assertEquals(RecordingIntegrity.CORRUPT, inspection.integrity());
    assertThrows(IOException.class, () -> AudioBlockRecordingReader.readAll(corrupt));
  }

  @Test
  void oversizedFrameCountIsRejectedBeforeSampleAllocation(@TempDir Path directory)
      throws IOException {
    Path file = directory.resolve("oversized.aarec");
    try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(file))) {
      writeHeader(output, AudioBlockRecordingFormat.MAGIC, AudioBlockRecordingFormat.VERSION);
      output.writeInt(AudioBlockRecordingFormat.MAX_FRAMES_PER_BLOCK + 1);
    }

    RecordingInspection inspection = AudioBlockRecordingReader.inspect(file);

    assertEquals(RecordingIntegrity.CORRUPT, inspection.integrity());
    assertThrows(IOException.class, () -> AudioBlockRecordingReader.readAll(file));
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
            new AudioFormatDescriptor(48000f, 2, 16), new float[][] {{0f}, {0f}}, 1L, 0L);
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
      writeHeader(
          output, AudioBlockRecordingFormat.LEGACY_MAGIC, AudioBlockRecordingFormat.LEGACY_VERSION);
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

  private static void writeHeader(DataOutputStream output, int magic, int version)
      throws IOException {
    output.writeInt(magic);
    output.writeShort(version);
    output.writeShort(STEREO_44K.channels());
    output.writeFloat(STEREO_44K.sampleRate());
    output.writeShort(STEREO_44K.sourceSampleSizeInBits());
    output.writeShort(0);
  }
}
