package org.hammer.audio.experimental.acoustic.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.hammer.audio.experimental.acoustic.dataset.DatasetDescriptor;
import org.hammer.audio.experimental.acoustic.dataset.DatasetManifest;
import org.hammer.audio.experimental.acoustic.dataset.DatasetRecording;
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
    assertTrue(panel.evaluationSummaryText().contains("Evaluated"));
    assertTrue(panel.analyticsText().contains("Dataset Analytics"));
    assertTrue(panel.analyticsText().contains("Label Distribution"));
    assertTrue(panel.histogramText().contains("Feature Histograms"));
    assertTrue(panel.histogramText().contains("Dominant Frequency"));
    assertTrue(panel.calibrationText().contains("Generator Calibration (Imported Dataset)"));
    assertTrue(panel.calibrationText().contains("Dataset Provenance"));
    assertTrue(panel.calibrationText().contains("Extracted feature vectors: 1"));
    assertTrue(panel.calibrationText().contains("Feature extraction: WingbeatFrequencyTracker"));
    assertTrue(panel.calibrationText().contains("Tiny dataset warning"));
  }

  @Test
  void calibrationCanRunOnSelectedSubsetAndReportsPartialAnnotations() throws IOException {
    Path root = tempDir.resolve("humbugdb-subset");
    Path audioDir = Files.createDirectories(root.resolve("data/audio"));
    Path metadataDir = Files.createDirectories(root.resolve("data/metadata"));
    createSineWave(audioDir.resolve("clip-a.wav"), 8_192, 480.0, 1.0);
    createSineWave(audioDir.resolve("clip-b.wav"), 8_192, 700.0, 1.0);
    Files.writeString(
        metadataDir.resolve("clips.csv"),
        """
        id,length,name,sample_rate,sound_type,species,gender
        clip-a,1.0,clip-a.wav,8192,mosquito,anopheles gambiae,female
        clip-b,1.0,clip-b.wav,8192,mosquito,,
        """);
    DatasetManifest manifest = new HumBugDbImporter().importFrom(root);

    ImportedRecordingWorkbenchPanel panel = new ImportedRecordingWorkbenchPanel();
    panel.loadManifest(manifest);
    panel.runCalibrationHeadless(true);

    assertTrue(panel.calibrationText().contains("Scope: Selected recording only"));
    assertTrue(panel.calibrationText().contains("Calibrated recordings: 1"));
    panel.runCalibrationHeadless(false);
    assertTrue(panel.calibrationText().contains("Scope: All imported recordings"));
    assertTrue(panel.calibrationText().contains("Calibrated recordings: 2"));
    assertTrue(panel.calibrationText().contains("Partial annotation warning"));
  }

  @Test
  void emptyManifestShowsDeterministicCalibrationMessage() throws IOException {
    DatasetManifest manifest =
        new DatasetManifest(
            new DatasetDescriptor(
                "empty-fixture",
                "Empty fixture",
                URI.create("https://example.org/empty"),
                "test-only",
                tempDir.toAbsolutePath(),
                Map.of("id", "fixture id")),
            List.of());
    ImportedRecordingWorkbenchPanel panel = new ImportedRecordingWorkbenchPanel();
    panel.loadManifest(manifest);
    assertTrue(
        panel
            .calibrationText()
            .contains("Calibration unavailable: dataset contains no recordings."));
  }

  @Test
  void calibrationWarnsWhenRecordingHasNoAnnotationSpans() throws IOException {
    Path root = tempDir.resolve("no-annotations");
    Path audioDir = Files.createDirectories(root.resolve("data/audio"));
    createSineWave(audioDir.resolve("clip.wav"), 8_192, 520.0, 1.0);
    DatasetManifest manifest =
        new DatasetManifest(
            new DatasetDescriptor(
                "annotation-fixture",
                "Annotation fixture",
                URI.create("https://example.org/annotation-fixture"),
                "test-only",
                root.toAbsolutePath(),
                Map.of("id", "fixture id")),
            List.of(
                new DatasetRecording(
                    "clip-no-annotation",
                    Path.of("data/audio/clip.wav"),
                    8_192.0,
                    1.0,
                    Map.of("species", "anopheles gambiae", "gender", "female"),
                    List.of(),
                    Map.of("sound_type", "mosquito"))));

    ImportedRecordingWorkbenchPanel panel = new ImportedRecordingWorkbenchPanel();
    panel.loadManifest(manifest);
    panel.runCalibrationHeadless(false);

    assertTrue(panel.calibrationText().contains("without annotation span(s)"));
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
