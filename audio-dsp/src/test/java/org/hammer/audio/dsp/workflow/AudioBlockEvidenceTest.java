package org.hammer.audio.dsp.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Map;
import org.hammer.audio.core.AudioBlock;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.junit.jupiter.api.Test;

class AudioBlockEvidenceTest {

  @Test
  void digestIsStableAndSensitiveToSampleBitsAndFormat() {
    AudioFormatDescriptor mono = new AudioFormatDescriptor(8_000.0f, 1, 32);
    AudioFormatDescriptor stereo = new AudioFormatDescriptor(8_000.0f, 2, 32);
    AudioBlock first = new AudioBlock(mono, new float[][] {{0.0f, 0.5f}}, 0L, 11L);
    AudioBlock sameSamplesDifferentRuntimeTime =
        new AudioBlock(mono, new float[][] {{0.0f, 0.5f}}, 99L, 999L);
    AudioBlock changedSample = new AudioBlock(mono, new float[][] {{0.0f, 0.25f}}, 0L, 0L);
    AudioBlock changedFormat =
        new AudioBlock(stereo, new float[][] {{0.0f, 0.5f}, {0.0f, 0.5f}}, 0L, 0L);

    String firstDigest = digest(first);

    assertEquals(firstDigest, digest(sameSamplesDifferentRuntimeTime));
    assertNotEquals(firstDigest, digest(changedSample));
    assertNotEquals(firstDigest, digest(changedFormat));
  }

  private static String digest(AudioBlock block) {
    Map<String, String> artifacts = AudioBlockEvidence.artifacts(block, "node.output", "audio-out");
    return artifacts.get(DeterministicAudioArtifacts.OUTPUT_DIGEST_SHA256);
  }
}
