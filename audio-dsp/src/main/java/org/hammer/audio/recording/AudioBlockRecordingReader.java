package org.hammer.audio.recording;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;

/**
 * Streaming reader for legacy version-1 and integrity-protected version-2 recordings.
 *
 * <p>Strict readers reject missing or invalid version-2 footers. Inspection/recovery readers retain
 * all complete frame records and classify the source instead of presenting it as complete.
 */
public final class AudioBlockRecordingReader implements Closeable {

  private final DigestInputStream digestStream;
  private final DataInputStream in;
  private final MessageDigest digest;
  private final AudioFormatDescriptor format;
  private final int formatVersion;
  private final boolean legacy;
  private final boolean allowIncomplete;

  private long blockCount;
  private long totalFrames;
  private long firstFrameIndex = Long.MIN_VALUE;
  private long lastFrameIndex = Long.MIN_VALUE;
  private long firstTimestampNanos = Long.MIN_VALUE;
  private long lastTimestampNanos = Long.MIN_VALUE;
  private long expectedNextFrameIndex = Long.MIN_VALUE;
  private long continuityGapCount;
  private long payloadBytes;
  private RecordingInspection inspection;
  private boolean closed;
  private boolean ended;

  /** Open a strict reader for a recording file. */
  public static AudioBlockRecordingReader open(Path file) throws IOException {
    return open(file, false);
  }

  private static AudioBlockRecordingReader open(Path file, boolean allowIncomplete)
      throws IOException {
    Objects.requireNonNull(file, "file");
    return new AudioBlockRecordingReader(Files.newInputStream(file), allowIncomplete);
  }

  /** Read the entire recording and require a valid footer for version 2. */
  public static List<AudioBlock> readAll(Path file) throws IOException {
    try (AudioBlockRecordingReader reader = open(file)) {
      List<AudioBlock> blocks = new ArrayList<>();
      Optional<AudioBlock> next = reader.next();
      while (next.isPresent()) {
        blocks.add(next.get());
        next = reader.next();
      }
      return Collections.unmodifiableList(blocks);
    }
  }

  /** Inspect a recording without allocating one list containing all samples. */
  public static RecordingInspection inspect(Path file) throws IOException {
    try (AudioBlockRecordingReader reader = open(file, true)) {
      Optional<AudioBlock> next = reader.next();
      while (next.isPresent()) {
        next = reader.next();
      }
      return reader
          .inspection()
          .orElseThrow(() -> new IOException("Recording inspection incomplete"));
    }
  }

  /**
   * Recover every complete readable block into a new finalized version-2 recording. The source is
   * never modified.
   */
  public static RecordingInspection recover(Path source, Path target) throws IOException {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");
    try (AudioBlockRecordingReader reader = open(source, true);
        AudioBlockRecordingWriter writer = AudioBlockRecordingWriter.open(target)) {
      Optional<AudioBlock> next = reader.next();
      while (next.isPresent()) {
        writer.write(next.get());
        next = reader.next();
      }
      RecordingInspection sourceInspection =
          reader.inspection().orElseThrow(() -> new IOException("Source inspection incomplete"));
      if (sourceInspection.blockCount() == 0L) {
        writer.abort();
        Path partialFile = writer.partialFile();
        if (partialFile != null) {
          Files.deleteIfExists(partialFile);
        }
        throw new IOException("No complete audio blocks can be recovered from " + source);
      }
    }
    return inspect(target);
  }

  /** Wrap an existing stream using strict version-2 validation. */
  public AudioBlockRecordingReader(InputStream stream) throws IOException {
    this(stream, false);
  }

  private AudioBlockRecordingReader(InputStream stream, boolean allowIncomplete)
      throws IOException {
    this.digest = newSha256();
    this.digestStream =
        new DigestInputStream(
            new BufferedInputStream(Objects.requireNonNull(stream, "stream")), digest);
    this.in = new DataInputStream(digestStream);
    this.allowIncomplete = allowIncomplete;
    Header header = readHeader();
    this.format = header.format();
    this.formatVersion = header.version();
    this.legacy = header.legacy();
  }

  /** Returns the normalized recording format. */
  public AudioFormatDescriptor format() {
    return format;
  }

  /** Returns the parsed format version. */
  public int formatVersion() {
    return formatVersion;
  }

  /** Returns the final inspection after EOF/footer was processed. */
  public Optional<RecordingInspection> inspection() {
    return Optional.ofNullable(inspection);
  }

  /** Read the next block or an empty value after the footer/end of input. */
  public Optional<AudioBlock> next() throws IOException {
    if (closed || ended) {
      return Optional.empty();
    }
    int frames;
    try {
      frames = in.readInt();
    } catch (EOFException exception) {
      if (legacy) {
        inspection =
            createInspection(
                RecordingIntegrity.LEGACY_UNVERIFIED,
                "",
                "Legacy recording reached a clean end of file without a completion footer.");
        ended = true;
        return Optional.empty();
      }
      return handleUnexpectedEnd("end of file reached before version-2 footer", exception);
    }
    if (!legacy && frames == AudioBlockRecordingFormat.FOOTER_MARKER) {
      return readAndValidateFooter();
    }
    if (frames < 0 || frames > AudioBlockRecordingFormat.MAX_FRAMES_PER_BLOCK) {
      return handleCorruption("invalid frame count: " + frames, null);
    }
    long sampleValues = (long) frames * format.channels();
    if (sampleValues > AudioBlockRecordingFormat.MAX_SAMPLE_VALUES_PER_BLOCK) {
      return handleCorruption("recording block is too large: " + sampleValues + " samples", null);
    }

    try {
      long frameIndex = in.readLong();
      long timestampNanos = in.readLong();
      float[][] samples = new float[format.channels()][frames];
      for (int channel = 0; channel < format.channels(); channel++) {
        for (int frame = 0; frame < frames; frame++) {
          samples[channel][frame] = in.readFloat();
        }
      }
      acceptRecord(frames, frameIndex, timestampNanos);
      return Optional.of(new AudioBlock(format, samples, frameIndex, timestampNanos));
    } catch (EOFException exception) {
      return handleTruncation("recording ended inside an audio block", exception);
    }
  }

  private Header readHeader() throws IOException {
    int magic = in.readInt();
    int version = in.readUnsignedShort();
    boolean legacyHeader =
        magic == AudioBlockRecordingFormat.LEGACY_MAGIC
            && version == AudioBlockRecordingFormat.LEGACY_VERSION;
    boolean currentHeader =
        magic == AudioBlockRecordingFormat.MAGIC && version == AudioBlockRecordingFormat.VERSION;
    if (!legacyHeader && !currentHeader) {
      throw new IOException(
          String.format(
              "unsupported audio recording header magic=0x%08x version=%d", magic, version));
    }
    int channels = in.readUnsignedShort();
    float sampleRate = in.readFloat();
    int sourceBits = in.readUnsignedShort();
    in.readUnsignedShort();
    if (channels < 1 || sourceBits < 1 || !(sampleRate > 0f) || !Float.isFinite(sampleRate)) {
      throw new IOException(
          "invalid header values: channels="
              + channels
              + " sampleRate="
              + sampleRate
              + " sourceBits="
              + sourceBits);
    }
    return new Header(
        version, new AudioFormatDescriptor(sampleRate, channels, sourceBits), legacyHeader);
  }

  private void acceptRecord(int frames, long frameIndex, long timestampNanos) {
    if (blockCount == 0L) {
      firstFrameIndex = frameIndex;
      firstTimestampNanos = timestampNanos;
    } else if (frameIndex != expectedNextFrameIndex) {
      continuityGapCount++;
    }
    blockCount++;
    totalFrames = Math.addExact(totalFrames, frames);
    lastFrameIndex = frameIndex;
    lastTimestampNanos = timestampNanos;
    expectedNextFrameIndex = Math.addExact(frameIndex, frames);
    payloadBytes = Math.addExact(payloadBytes, 20L + 4L * format.channels() * frames);
  }

  private Optional<AudioBlock> readAndValidateFooter() throws IOException {
    digestStream.on(false);
    byte[] calculatedChecksum = digest.digest();
    try {
      int footerMagic = in.readInt();
      long declaredBlocks = in.readLong();
      long declaredFrames = in.readLong();
      long declaredFirstFrame = in.readLong();
      long declaredLastFrame = in.readLong();
      long declaredFirstTimestamp = in.readLong();
      long declaredLastTimestamp = in.readLong();
      long declaredGaps = in.readLong();
      long declaredPayloadBytes = in.readLong();
      int checksumLength = in.readInt();
      if (footerMagic != AudioBlockRecordingFormat.FOOTER_MAGIC
          || checksumLength != AudioBlockRecordingFormat.SHA_256_BYTES) {
        return handleCorruption("invalid version-2 completion footer", null);
      }
      byte[] declaredChecksum = in.readNBytes(checksumLength);
      if (declaredChecksum.length != checksumLength) {
        return handleTruncation("recording ended inside completion footer", null);
      }
      boolean countersMatch =
          declaredBlocks == blockCount
              && declaredFrames == totalFrames
              && declaredFirstFrame == firstFrameIndex
              && declaredLastFrame == lastFrameIndex
              && declaredFirstTimestamp == firstTimestampNanos
              && declaredLastTimestamp == lastTimestampNanos
              && declaredGaps == continuityGapCount
              && declaredPayloadBytes == payloadBytes;
      if (!countersMatch || !Arrays.equals(declaredChecksum, calculatedChecksum)) {
        return handleCorruption("completion footer counters or SHA-256 digest do not match", null);
      }
      inspection =
          createInspection(
              RecordingIntegrity.COMPLETE,
              toHex(calculatedChecksum),
              continuityGapCount == 0L
                  ? "Recording finalized cleanly."
                  : "Recording finalized with source-frame discontinuities.");
      ended = true;
      return Optional.empty();
    } catch (EOFException exception) {
      return handleTruncation("recording ended inside completion footer", exception);
    }
  }

  private Optional<AudioBlock> handleTruncation(String detail, Exception cause) throws IOException {
    if (allowIncomplete) {
      inspection = createInspection(RecordingIntegrity.TRUNCATED, "", detail);
      ended = true;
      return Optional.empty();
    }
    EOFException exception = new EOFException(detail);
    if (cause != null) {
      exception.initCause(cause);
    }
    throw exception;
  }

  private Optional<AudioBlock> handleUnexpectedEnd(String detail, Exception cause)
      throws IOException {
    if (allowIncomplete) {
      inspection = createInspection(RecordingIntegrity.RECOVERABLE_INCOMPLETE, "", detail);
      ended = true;
      return Optional.empty();
    }
    EOFException exception = new EOFException(detail);
    if (cause != null) {
      exception.initCause(cause);
    }
    throw exception;
  }

  private Optional<AudioBlock> handleCorruption(String detail, Exception cause) throws IOException {
    if (allowIncomplete) {
      inspection = createInspection(RecordingIntegrity.CORRUPT, "", detail);
      ended = true;
      return Optional.empty();
    }
    IOException exception = new IOException(detail);
    if (cause != null) {
      exception.initCause(cause);
    }
    throw exception;
  }

  private RecordingInspection createInspection(
      RecordingIntegrity integrity, String checksum, String detail) {
    return new RecordingInspection(
        formatVersion,
        format,
        integrity,
        blockCount,
        totalFrames,
        firstFrameIndex,
        lastFrameIndex,
        firstTimestampNanos,
        lastTimestampNanos,
        continuityGapCount,
        payloadBytes,
        checksum,
        detail);
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    in.close();
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
    }
  }

  private static String toHex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
      result.append(Character.forDigit(value & 0x0f, 16));
    }
    return result.toString();
  }

  private record Header(int version, AudioFormatDescriptor format, boolean legacy) { }
}
