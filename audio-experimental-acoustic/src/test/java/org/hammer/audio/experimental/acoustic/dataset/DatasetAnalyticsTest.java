package org.hammer.audio.experimental.acoustic.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatasetAnalyticsTest {

  @TempDir Path tempDir;

  @Test
  void computeReturnsCorrectRecordingCount() throws IOException {
    DatasetManifest manifest = buildManifest();

    DatasetAnalytics analytics = DatasetAnalytics.compute(manifest);

    assertEquals(2, analytics.recordingCount());
    assertEquals("HumBugDB", analytics.datasetName());
  }

  @Test
  void computePopulatesLabelDistribution() throws IOException {
    DatasetManifest manifest = buildManifest();

    DatasetAnalytics analytics = DatasetAnalytics.compute(manifest);

    assertNotNull(analytics.labelDistribution().get("gender"));
    assertEquals(1, analytics.labelDistribution().get("gender").getOrDefault("female", 0));
    assertEquals(1, analytics.labelDistribution().get("gender").getOrDefault("male", 0));
  }

  @Test
  void computePopulatesSampleRateCounts() throws IOException {
    DatasetManifest manifest = buildManifest();

    DatasetAnalytics analytics = DatasetAnalytics.compute(manifest);

    assertEquals(2, analytics.sampleRateCounts().getOrDefault(8192, 0));
  }

  @Test
  void computePopulatesDurationStats() throws IOException {
    DatasetManifest manifest = buildManifest();

    DatasetAnalytics analytics = DatasetAnalytics.compute(manifest);

    assertEquals(2, analytics.durationStats().count());
    assertTrue(analytics.durationStats().min() > 0.0);
    assertTrue(analytics.durationStats().max() >= analytics.durationStats().min());
  }

  @Test
  void toMarkdownReportContainsExpectedSections() throws IOException {
    DatasetManifest manifest = buildManifest();

    String report = DatasetAnalytics.compute(manifest).toMarkdownReport();

    assertTrue(report.contains("Dataset Analytics"));
    assertTrue(report.contains("Label Distribution"));
    assertTrue(report.contains("Sample Rate Distribution"));
    assertTrue(report.contains("Duration Distribution"));
    assertTrue(report.contains("gender"));
  }

  private DatasetManifest buildManifest() throws IOException {
    Path root = tempDir.resolve("humbugdb");
    Path audioDir = Files.createDirectories(root.resolve("data/audio"));
    Path metadataDir = Files.createDirectories(root.resolve("data/metadata"));
    createSineWave(audioDir.resolve("female.wav"), 8_192, 480.0, 1.0);
    createSineWave(audioDir.resolve("male.wav"), 8_192, 650.0, 1.0);
    Files.writeString(
        metadataDir.resolve("species.csv"),
        """
        id,length,name,sample_rate,sound_type,species,gender
        female,1.0,female.wav,8192,mosquito,anopheles gambiae,female
        male,1.0,male.wav,8192,mosquito,anopheles funestus,male
        """);
    return new HumBugDbImporter().importFrom(root);
  }

  private static void createSineWave(Path file, int sampleRate, double frequencyHz, double seconds)
      throws IOException {
    int frames = (int) Math.round(sampleRate * seconds);
    byte[] pcm = new byte[frames * 2];
    for (int i = 0; i < frames; i++) {
      short sample =
          (short)
              Math.round(
                  Math.sin(2.0 * Math.PI * frequencyHz * i / sampleRate) * 0.8 * Short.MAX_VALUE);
      pcm[i * 2] = (byte) (sample & 0xFF);
      pcm[i * 2 + 1] = (byte) ((sample >>> 8) & 0xFF);
    }
    AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
    try (AudioInputStream stream =
        new AudioInputStream(new ByteArrayInputStream(pcm), format, frames)) {
      AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file.toFile());
    }
  }
}
