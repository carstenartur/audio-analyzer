package org.hammer.audio.recording;

import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;

/**
 * Writes streamable, integrity-protected version-2 audio recordings.
 *
 * <p>Path-backed writers use a sibling {@code .partial} file and move it to the requested target
 * only after the completion footer has been flushed successfully. Instances are not thread-safe.
 */
public final class AudioBlockRecordingWriter implements Closeable {

  private final CountingOutputStream counter;
  private final DigestOutputStream digestStream;
  private final DataOutputStream out;
  private final MessageDigest digest;
  private final Path targetFile;
  private final Path partialFile;

  private AudioFormatDescriptor format;
  private long blocksWritten;
  private long totalFrames;
  private long firstFrameIndex = Long.MIN_VALUE;
  private long lastFrameIndex = Long.MIN_VALUE;
  private long firstTimestampNanos = Long.MIN_VALUE;
  private long lastTimestampNanos = Long.MIN_VALUE;
  private long expectedNextFrameIndex = Long.MIN_VALUE;
  private long continuityGapCount;
  private long payloadBytes;
  private boolean closed;
  private boolean finalized;

  /** Open an atomic path-backed writer. */
  public static AudioBlockRecordingWriter open(Path file) throws IOException {
    Objects.requireNonNull(file, "file");
    Path target = file.toAbsolutePath().normalize();
    Path parent = target.getParent();
    if (parent == null) {
      throw new IOException("Recording target has no parent directory: " + target);
    }
    Files.createDirectories(parent);
    Path partial = target.resolveSibling(target.getFileName() + ".partial");
    Files.deleteIfExists(partial);
    OutputStream stream =
        Files.newOutputStream(
            partial,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING);
    return new AudioBlockRecordingWriter(stream, target, partial);
  }

  /** Wrap an existing output stream; no path move is performed on close. */
  public AudioBlockRecordingWriter(OutputStream stream) {
    this(stream, null, null);
  }

  private AudioBlockRecordingWriter(OutputStream stream, Path targetFile, Path partialFile) {
    this.digest = newSha256();
    this.counter = new CountingOutputStream(Objects.requireNonNull(stream, "stream"));
    this.digestStream = new DigestOutputStream(counter, digest);
    this.out = new DataOutputStream(digestStream);
    this.targetFile = targetFile;
    this.partialFile = partialFile;
  }

  /** Returns the format header already written, or {@code null} before the first block. */
  public AudioFormatDescriptor format() {
    return format;
  }

  /** Number of blocks successfully serialized. */
  public long blocksWritten() {
    return blocksWritten;
  }

  /** Number of frames successfully serialized. */
  public long totalFrames() {
    return totalFrames;
  }

  /** Number of detected source-frame discontinuities. */
  public long continuityGapCount() {
    return continuityGapCount;
  }

  /** Bytes written to the partial/output stream, including the current header/footer state. */
  public long bytesWritten() {
    return counter.count();
  }

  /** Target path, or {@code null} for stream-backed writers. */
  public Path targetFile() {
    return targetFile;
  }

  /** Partial path, or {@code null} for stream-backed writers. */
  public Path partialFile() {
    return partialFile;
  }

  /** Append one immutable audio block. */
  public void write(AudioBlock block) throws IOException {
    Objects.requireNonNull(block, "block");
    if (closed) {
      throw new IllegalStateException("writer is closed");
    }
    if (format == null) {
      writeHeader(block.format());
      format = block.format();
    } else if (!format.equals(block.format())) {
      throw new IllegalStateException(
          "format mismatch: expected " + format + " but block was " + block.format());
    }
    if (blocksWritten == 0L) {
      firstFrameIndex = block.frameIndex();
      firstTimestampNanos = block.timestampNanos();
    } else if (block.frameIndex() != expectedNextFrameIndex) {
      continuityGapCount++;
    }

    int frames = block.frames();
    int channels = block.channels();
    out.writeInt(frames);
    out.writeLong(block.frameIndex());
    out.writeLong(block.timestampNanos());
    for (int channel = 0; channel < channels; channel++) {
      float[] samples = block.channelView(channel);
      for (int frame = 0; frame < frames; frame++) {
        out.writeFloat(samples[frame]);
      }
    }
    blocksWritten++;
    totalFrames = Math.addExact(totalFrames, frames);
    lastFrameIndex = block.frameIndex();
    lastTimestampNanos = block.timestampNanos();
    expectedNextFrameIndex = Math.addExact(block.frameIndex(), frames);
    payloadBytes = Math.addExact(payloadBytes, 20L + 4L * channels * frames);
  }

  /**
   * Abort without a completion footer. A path-backed writer leaves the sibling partial file for
   * inspection/recovery and never replaces the requested target.
   */
  public void abort() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    out.close();
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    if (format == null) {
      abort();
      if (partialFile != null) {
        Files.deleteIfExists(partialFile);
      }
      return;
    }
    IOException failure = null;
    try {
      writeFooter();
      out.flush();
      finalized = true;
    } catch (IOException exception) {
      failure = exception;
    }
    try {
      out.close();
    } catch (IOException exception) {
      if (failure == null) {
        failure = exception;
      } else {
        failure.addSuppressed(exception);
      }
    }
    closed = true;
    if (failure == null && targetFile != null) {
      moveFinalizedPartial();
    }
    if (failure != null) {
      throw failure;
    }
  }

  /** Returns whether the completion footer was written successfully. */
  public boolean finalized() {
    return finalized;
  }

  private void writeHeader(AudioFormatDescriptor descriptor) throws IOException {
    out.writeInt(AudioBlockRecordingFormat.MAGIC);
    out.writeShort(AudioBlockRecordingFormat.VERSION);
    out.writeShort(descriptor.channels());
    out.writeFloat(descriptor.sampleRate());
    out.writeShort(descriptor.sourceSampleSizeInBits());
    out.writeShort(0);
  }

  private void writeFooter() throws IOException {
    out.writeInt(AudioBlockRecordingFormat.FOOTER_MARKER);
    out.flush();
    digestStream.on(false);
    byte[] checksum = digest.digest();
    out.writeInt(AudioBlockRecordingFormat.FOOTER_MAGIC);
    out.writeLong(blocksWritten);
    out.writeLong(totalFrames);
    out.writeLong(firstFrameIndex);
    out.writeLong(lastFrameIndex);
    out.writeLong(firstTimestampNanos);
    out.writeLong(lastTimestampNanos);
    out.writeLong(continuityGapCount);
    out.writeLong(payloadBytes);
    out.writeInt(checksum.length);
    out.write(checksum);
  }

  private void moveFinalizedPartial() throws IOException {
    try {
      Files.move(
          partialFile,
          targetFile,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException exception) {
      Files.move(partialFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
    }
  }

  private static final class CountingOutputStream extends FilterOutputStream {

    private long count;

    private CountingOutputStream(OutputStream stream) {
      super(stream);
    }

    @Override
    public void write(int value) throws IOException {
      out.write(value);
      count++;
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      out.write(bytes, offset, length);
      count = Math.addExact(count, length);
    }

    private long count() {
      return count;
    }
  }
}
