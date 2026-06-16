package org.hammer.audio.experimental.acoustic.workbench;

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
import org.hammer.audio.experimental.acoustic.dataset.DatasetManifest;
import org.hammer.audio.experimental.acoustic.dataset.HumBugDbImporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportedRecordingWorkbenchPanelTest {

  @TempDir Path tempDir;

  @Test
  void panelCanLoadImportedManifestHeadlessly() throws IOException {
    Path root = tempDir.resolve("humbugdb");
    Path audioDir = Files.createDirectories(root.resolve("data/audio"));
    Path metadataDir = Files.createDirectories(root.resolve("data/metadata"));
    createSineWave(audioDir.resolve("clip.wav"), 8_192, 500.0, 1.0);
    Files.writeString(
        metadataDir.resolve("clips.csv"),
        """
        id,length,name,sample_rate,sound_type,species,gender
        clip-1,1.0,clip.wav,8192,mosquito,anopheles gambiae,female
        """);
    DatasetManifest manifest = new HumBugDbImporter().importFrom(root);

    ImportedRecordingWorkbenchPanel panel = new ImportedRecordingWorkbenchPanel();
    panel.loadManifest(manifest);

    assertNotNull(panel.manifest());
    assertEquals(1, panel.manifest().recordings().size());
    assertTrue(panel.recordingSummaryText().contains("Predicted label"));
    assertTrue(panel.recordingSummaryText().contains("clip-1"));
    assertTrue(panel.evaluationSummaryText().contains("Imported Dataset Evaluation"));
    assertTrue(panel.evaluationSummaryText().contains("Accuracy"));
    assertTrue(panel.evaluationSummaryText().contains("Confusion Matrix"));
    assertTrue(panel.analyticsText().contains("Dataset Analytics"));
    assertTrue(panel.analyticsText().contains("Label Distribution"));
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
