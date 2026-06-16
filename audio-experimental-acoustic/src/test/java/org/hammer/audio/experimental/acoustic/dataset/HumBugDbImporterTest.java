package org.hammer.audio.experimental.acoustic.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class HumBugDbImporterTest {

  @TempDir Path tempDir;

  @Test
  void importFromBuildsManifestFromLocalHumBugDbLayout() throws IOException {
    Path root = tempDir.resolve("humbugdb");
    Path audioDir = Files.createDirectories(root.resolve("data/audio"));
    Path metadataDir = Files.createDirectories(root.resolve("data/metadata"));
    createSineWave(audioDir.resolve("clip-001.wav"), 8_000, 440.0, 1.0);
    Files.writeString(
        metadataDir.resolve("subset.csv"),
        """
        id,length,name,sample_rate,sound_type,species,gender,fed,place,location_type
        rec-001,1.0,clip-001.wav,8000,mosquito,anopheles gambiae,female,yes,lab,cup
        """);

    DatasetManifest manifest = new HumBugDbImporter().importFrom(root);

    assertEquals("humbugdb", manifest.descriptor().id());
    assertEquals("HumBugDB", manifest.descriptor().name());
    assertEquals(root.toAbsolutePath(), manifest.descriptor().localRootPath());
    assertEquals(1, manifest.recordings().size());
    DatasetRecording recording = manifest.recordings().getFirst();
    assertEquals("rec-001", recording.recordingId());
    assertEquals(Path.of("data/audio/clip-001.wav"), recording.audioPath());
    assertEquals(8_000.0, recording.sampleRateHz());
    assertEquals(1.0, recording.durationSeconds(), 1e-9);
    assertEquals("anopheles gambiae", recording.labels().get("species"));
    assertEquals("female", recording.labels().get("gender"));
    assertEquals("yes", recording.labels().get("fed"));
    assertEquals(1, recording.annotations().size());
    assertEquals("mosquito", recording.annotations().getFirst().label());
    assertEquals("lab", recording.metadata().get("place"));
    assertEquals("cup", recording.metadata().get("location_type"));
    assertEquals("data/metadata/subset.csv", recording.metadata().get("metadata_csv"));
  }

  @Test
  void importFromPreservesTimedAnnotationsWhenColumnsExist() throws IOException {
    Path root = tempDir.resolve("humbugdb-annotated");
    Path audioDir = Files.createDirectories(root.resolve("data/audio/session-a"));
    Path metadataDir = Files.createDirectories(root.resolve("data/metadata"));
    createSineWave(audioDir.resolve("clip-002.wav"), 16_000, 620.0, 2.0);
    Files.writeString(
        metadataDir.resolve("events.csv"),
        """
        id,name,start_time,end_time,event_label,sound_type,gender,province
        rec-002,session-a/clip-002.wav,0.25,0.75,mosquito_event,mosquito,male,Arusha
        """);

    DatasetManifest manifest = new HumBugDbImporter().importFrom(root);

    DatasetRecording recording = manifest.recordings().getFirst();
    assertEquals(16_000.0, recording.sampleRateHz(), 1e-9);
    assertEquals(2.0, recording.durationSeconds(), 1e-9);
    assertEquals("male", recording.labels().get("gender"));
    assertEquals(1, recording.annotations().size());
    DatasetAnnotation annotation = recording.annotations().getFirst();
    assertEquals(0.25, annotation.startSeconds(), 1e-9);
    assertEquals(0.75, annotation.endSeconds(), 1e-9);
    assertEquals("mosquito_event", annotation.label());
    assertEquals("Arusha", recording.metadata().get("province"));
    assertFalse(recording.metadata().containsKey("sound_type"));
    assertTrue(recording.metadata().containsKey("metadata_csv"));
  }

  @Test
  void importFromHandlesQuotedFieldsWithCommasAndUtf8() throws IOException {
    Path root = tempDir.resolve("humbugdb-csv");
    Path audioDir = Files.createDirectories(root.resolve("data/audio"));
    Path metadataDir = Files.createDirectories(root.resolve("data/metadata"));
    createSineWave(audioDir.resolve("clip-q.wav"), 8_000, 300.0, 0.5);
    // place field contains a comma inside quotes; species contains a UTF-8 character
    Files.writeString(
        metadataDir.resolve("quoted.csv"),
        "id,length,name,sound_type,species,place\n"
            + "rec-q,0.5,clip-q.wav,mosquito,Aedes \u00e6gypti,\"Nairobi, Kenya\"\n",
        java.nio.charset.StandardCharsets.UTF_8);

    DatasetManifest manifest = new HumBugDbImporter().importFrom(root);

    assertEquals(1, manifest.recordings().size());
    DatasetRecording recording = manifest.recordings().getFirst();
    assertEquals("Aedes \u00e6gypti", recording.labels().get("species"));
    assertEquals("Nairobi, Kenya", recording.metadata().get("place"));
  }

  @Test
  void importFromHandlesEscapedDoubleQuotesInCsvFields() throws IOException {
    Path root = tempDir.resolve("humbugdb-escaped");
    Path audioDir = Files.createDirectories(root.resolve("data/audio"));
    Path metadataDir = Files.createDirectories(root.resolve("data/metadata"));
    createSineWave(audioDir.resolve("clip-e.wav"), 8_000, 300.0, 0.5);
    // place uses "" inside a quoted field to represent a literal double-quote
    Files.writeString(
        metadataDir.resolve("escaped.csv"),
        "id,length,name,sound_type,place\n" + "rec-e,0.5,clip-e.wav,mosquito,\"lab \"\"A\"\"\"\n",
        java.nio.charset.StandardCharsets.UTF_8);

    DatasetManifest manifest = new HumBugDbImporter().importFrom(root);

    assertEquals(1, manifest.recordings().size());
    DatasetRecording recording = manifest.recordings().getFirst();
    assertEquals("lab \"A\"", recording.metadata().get("place"));
  }

  @Test
  void importFromSetsLicenseToCreativeCommonsBy40() throws IOException {
    Path root = tempDir.resolve("humbugdb-license");
    Path audioDir = Files.createDirectories(root.resolve("data/audio"));
    Path metadataDir = Files.createDirectories(root.resolve("data/metadata"));
    createSineWave(audioDir.resolve("clip-l.wav"), 8_000, 300.0, 0.5);
    Files.writeString(
        metadataDir.resolve("meta.csv"),
        "id,length,name,sound_type\nrec-l,0.5,clip-l.wav,mosquito\n");

    DatasetManifest manifest = new HumBugDbImporter().importFrom(root);

    assertEquals("CC BY 4.0", manifest.descriptor().license());
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
