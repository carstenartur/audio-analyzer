package org.hammer.audio.experimental.acoustic.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DatasetManifestTest {

  @Test
  void descriptorRejectsRelativeRootPath() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DatasetDescriptor(
                "humbugdb",
                "HumBugDB",
                URI.create("https://example.invalid/humbugdb"),
                "CHECK_DATASET_RELEASE",
                Path.of("relative/path"),
                Map.of("species", "Mosquito species label")));
  }

  @Test
  void recordingRejectsNonPositiveSampleRate() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DatasetRecording(
                "rec-1",
                Path.of("audio/rec-1.wav"),
                0.0,
                1.0,
                Map.of("species", "anopheles"),
                List.of(),
                Map.of("country", "tanzania")));
  }

  @Test
  void annotationRejectsEndBeforeStart() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new DatasetAnnotation(0.6, 0.5, "mosquito_event", Map.of("votes", "12")));
  }

  @Test
  void manifestDefensivelyCopiesRecordings() {
    DatasetDescriptor descriptor =
        new DatasetDescriptor(
            "humbugdb",
            "HumBugDB",
            URI.create("https://example.invalid/humbugdb"),
            "CHECK_DATASET_RELEASE",
            Path.of("/tmp/humbugdb"),
            Map.of("species", "Mosquito species label"));
    DatasetRecording recording =
        new DatasetRecording(
            "rec-1",
            Path.of("audio/rec-1.wav"),
            8_000.0,
            1.0,
            Map.of("species", "anopheles"),
            List.of(new DatasetAnnotation(0.1, 0.9, "mosquito_event", Map.of("votes", "12"))),
            Map.of("country", "tanzania"));
    List<DatasetRecording> mutableRecordings = new java.util.ArrayList<>(List.of(recording));

    DatasetManifest manifest = new DatasetManifest(descriptor, mutableRecordings);
    mutableRecordings.clear();

    assertEquals(1, manifest.recordings().size());
  }
}
