package org.hammer.audio.dsp.workflow;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;

/** Produces canonical digests and stable numerical summaries for immutable audio blocks. */
final class AudioBlockEvidence {

  static final String DIGEST_ALGORITHM = "SHA-256";
  static final String DIGEST_ENCODING = "audio-block-f32be-planar-v1";
  private static final int PREVIEW_FRAMES = 16;

  private AudioBlockEvidence() {
    throw new UnsupportedOperationException("Utility class");
  }

  static Map<String, String> artifacts(AudioBlock block, String outputNodeId, String outputPortId) {
    Objects.requireNonNull(block, "block");
    Map<String, String> artifacts = new LinkedHashMap<>();
    artifacts.put(DeterministicAudioArtifacts.OUTPUT_NODE_ID, outputNodeId);
    artifacts.put(DeterministicAudioArtifacts.OUTPUT_PORT_ID, outputPortId);
    artifacts.put(DeterministicAudioArtifacts.OUTPUT_DIGEST_ALGORITHM, DIGEST_ALGORITHM);
    artifacts.put(DeterministicAudioArtifacts.OUTPUT_DIGEST_ENCODING, DIGEST_ENCODING);
    artifacts.put(DeterministicAudioArtifacts.OUTPUT_DIGEST_SHA256, digest(block));
    artifacts.put(
        DeterministicAudioArtifacts.OUTPUT_SAMPLE_RATE_HZ,
        Float.toString(block.format().sampleRate()));
    artifacts.put(DeterministicAudioArtifacts.OUTPUT_CHANNELS, Integer.toString(block.channels()));
    artifacts.put(DeterministicAudioArtifacts.OUTPUT_FRAMES, Integer.toString(block.frames()));
    Summary summary = summarize(block);
    artifacts.put(DeterministicAudioArtifacts.OUTPUT_MIN, Float.toHexString(summary.minimum()));
    artifacts.put(DeterministicAudioArtifacts.OUTPUT_MAX, Float.toHexString(summary.maximum()));
    artifacts.put(DeterministicAudioArtifacts.OUTPUT_MEAN, Double.toHexString(summary.mean()));
    artifacts.put(DeterministicAudioArtifacts.OUTPUT_RMS, Double.toHexString(summary.rms()));
    artifacts.put(
        DeterministicAudioArtifacts.OUTPUT_CHANNEL_ZERO_PREVIEW, channelZeroPreview(block));
    return Map.copyOf(artifacts);
  }

  private static String digest(AudioBlock block) {
    MessageDigest digest = sha256();
    AudioFormatDescriptor format = block.format();
    updateInt(digest, Float.floatToIntBits(format.sampleRate()));
    updateInt(digest, format.channels());
    updateInt(digest, format.sourceSampleSizeInBits());
    updateInt(digest, block.frames());
    for (int channel = 0; channel < block.channels(); channel++) {
      for (float sample : block.channelView(channel)) {
        updateInt(digest, Float.floatToIntBits(sample));
      }
    }
    return hex(digest.digest());
  }

  private static Summary summarize(AudioBlock block) {
    float minimum = Float.POSITIVE_INFINITY;
    float maximum = Float.NEGATIVE_INFINITY;
    double sum = 0.0d;
    double sumOfSquares = 0.0d;
    long sampleCount = 0L;
    for (int channel = 0; channel < block.channels(); channel++) {
      for (float sample : block.channelView(channel)) {
        if (!Float.isFinite(sample)) {
          throw new IllegalArgumentException("Audio output contains non-finite samples");
        }
        minimum = Math.min(minimum, sample);
        maximum = Math.max(maximum, sample);
        sum += sample;
        sumOfSquares += (double) sample * sample;
        sampleCount++;
      }
    }
    return new Summary(minimum, maximum, sum / sampleCount, Math.sqrt(sumOfSquares / sampleCount));
  }

  private static String channelZeroPreview(AudioBlock block) {
    StringJoiner preview = new StringJoiner(",");
    float[] samples = block.channelView(0);
    int previewLength = Math.min(samples.length, PREVIEW_FRAMES);
    for (int frame = 0; frame < previewLength; frame++) {
      preview.add(Float.toHexString(samples[frame]));
    }
    return preview.toString();
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance(DIGEST_ALGORITHM);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void updateInt(MessageDigest digest, int value) {
    digest.update((byte) (value >>> 24));
    digest.update((byte) (value >>> 16));
    digest.update((byte) (value >>> 8));
    digest.update((byte) value);
  }

  private static String hex(byte[] bytes) {
    StringBuilder text = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      text.append(Character.forDigit((value >>> 4) & 0x0f, 16));
      text.append(Character.forDigit(value & 0x0f, 16));
    }
    return text.toString();
  }

  private record Summary(float minimum, float maximum, double mean, double rms) {
    Summary {
      if (!Float.isFinite(minimum)
          || !Float.isFinite(maximum)
          || !Double.isFinite(mean)
          || !Double.isFinite(rms)) {
        throw new IllegalArgumentException("Audio evidence summary must remain finite");
      }
    }
  }
}
