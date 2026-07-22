package org.hammer.audio.recording;

/**
 * Binary on-disk format for normalized {@link org.hammer.audio.core.AudioBlock} recordings.
 *
 * <p>Version 1 used the {@code AAR1} magic and ended at EOF. Version 2 uses {@code AAR2}, retains
 * streamable frame records and adds a completion footer with counters and a SHA-256 digest.
 */
public final class AudioBlockRecordingFormat {

  /** Legacy magic header value ({@code 'A','A','R','1'}). */
  public static final int LEGACY_MAGIC = 0x41415231;

  /** Current magic header value ({@code 'A','A','R','2'}). */
  public static final int MAGIC = 0x41415232;

  /** Legacy format version accepted by the reader. */
  public static final int LEGACY_VERSION = 1;

  /** Current format version. */
  public static final int VERSION = 2;

  /** Dedicated binary media type for recording payloads. */
  public static final String MEDIA_TYPE = "application/vnd.carstenartur.audio-recording";

  /** Preferred non-conflicting extension for new recordings. */
  public static final String FILE_EXTENSION = "aarec";

  /** Legacy extension retained for import compatibility. */
  public static final String LEGACY_FILE_EXTENSION = "aar";

  /** Header size in bytes. */
  public static final int HEADER_BYTES = 4 + 2 + 2 + 4 + 2 + 2;

  /** Negative record marker introducing the version-2 completion footer. */
  static final int FOOTER_MARKER = -1;

  /** Footer magic ({@code 'E','N','D','2'}). */
  static final int FOOTER_MAGIC = 0x454e4432;

  /** SHA-256 digest length. */
  static final int SHA_256_BYTES = 32;

  /** Defensive upper bound for one decoded block's frame count. */
  static final int MAX_FRAMES_PER_BLOCK = 1_048_576;

  /** Defensive upper bound for all channel/frame float values allocated for one block. */
  static final long MAX_SAMPLE_VALUES_PER_BLOCK = 16_777_216L;

  private AudioBlockRecordingFormat() { }
}
