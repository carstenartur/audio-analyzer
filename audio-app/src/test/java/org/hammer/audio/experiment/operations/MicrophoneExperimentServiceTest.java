package org.hammer.audio.experiment.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;
import org.hammer.audio.acquisition.health.ExperimentHealthState;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MicrophoneExperimentServiceTest {

  @Test
  void liveWithoutHardwareIsBlockedBeforeSourceOpen(@TempDir Path directory) throws Exception {
    try (var service = service(directory)) {
      var profile = service.catalogue().profiles().getFirst();
      var config =
          new Configuration(
              "Live check", "LIVE", profile.id(), "single-source", "", 1, true, "capture.aarec");
      var result = service.preflight(config);
      assertEquals("STANDALONE_MICROPHONE", result.health().facts().get("readiness.scope"));
      assertTrue(result.health().facts().get("document.status").startsWith("Not applicable:"));
      assertTrue(result.health().facts().get("workflow.status").startsWith("Not applicable:"));
      assertEquals(ExperimentHealthState.BLOCKED, result.health().state());
      assertFalse(result.health().startAllowed());
      assertTrue(
          result.health().findings().stream()
              .anyMatch(item -> item.code().equals("source.live-device")));
      assertThrows(IllegalStateException.class, () -> service.start(config, result.token(), true));
    }
  }

  @Test
  void changedConfigurationCannotReusePreflight(@TempDir Path directory) throws Exception {
    try (var service = service(directory)) {
      var config = configuration(service);
      var preflight = service.preflight(config);
      var changed =
          new Configuration(
              "Changed",
              config.sourceMode(),
              config.profileId(),
              config.scenario(),
              "",
              2,
              true,
              "capture.aarec");
      assertThrows(
          IllegalStateException.class, () -> service.start(changed, preflight.token(), true));
    }
  }

  @Test
  void immediateStopRetainsProfileManifestAndIncompleteEvidence(@TempDir Path directory)
      throws Exception {
    try (var service = service(directory)) {
      var base = configuration(service);
      var longRun =
          new Configuration(
              "Early stop",
              base.sourceMode(),
              base.profileId(),
              base.scenario(),
              "",
              3600,
              true,
              "capture.aarec");
      var ready = service.preflight(longRun);
      service.start(longRun, ready.token(), true);
      service.stop();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
      while (!service.snapshot().exportAvailable() && System.nanoTime() < deadline) {
        Thread.sleep(10L);
      }
      var result = service.snapshot();
      assertTrue(
          result.exportAvailable(),
          "Early stop must finalize promptly, including before source creation");
      assertEquals(ExperimentHealthState.INCOMPLETE, result.health().state());
      assertEquals("false", result.health().facts().get("evidence.complete"));
      String manifest =
          java.nio.file.Files.readString(
              directory.resolve("runs").resolve(result.runId()).resolve("experiment.manifest"));
      assertTrue(manifest.contains("evidence.complete"));
      assertTrue(manifest.contains("rectangular-example"));
    }
  }

  @Test
  void exportRefusesManagedRunDirectoryToPreventSelfInclusion(@TempDir Path directory)
      throws Exception {
    Path workspace = directory.resolve("workspace");
    try (var service = service(workspace)) {
      var config = configuration(service);
      service.start(config, service.preflight(config).token(), true);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15L);
      while (!service.snapshot().exportAvailable() && System.nanoTime() < deadline) {
        Thread.sleep(10L);
      }
      Path run = workspace.resolve("runs").resolve(service.snapshot().runId());
      assertThrows(
          java.io.IOException.class, () -> service.exportEvidence(run.resolve("evidence.zip")));
      assertFalse(java.nio.file.Files.exists(run.resolve("evidence.zip")));
      Path export = directory.resolve("evidence.zip");
      service.exportEvidence(export);
      assertTrue(java.nio.file.Files.size(export) > 0L);
    }
  }

  @Test
  void inputEvidenceFilenameIsReserved() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Configuration(
                "Replay", "REPLAY", "profile", "single-source", "input", 1, true, "input.aarec"));
  }

  @Test
  void simulationProducesAssociatedRecordingAndHashedEvidence(@TempDir Path directory)
      throws Exception {
    try (var service = service(directory)) {
      var config = configuration(service);
      var ready = service.preflight(config);
      assertTrue(ready.health().startAllowed());
      service.start(config, ready.token(), true);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15L);
      while (!service.snapshot().exportAvailable() && System.nanoTime() < deadline) {
        Thread.sleep(20L);
      }
      var result = service.snapshot();
      assertEquals(ExperimentHealthState.COMPLETED, result.health().state());
      assertEquals("COMPLETE", result.health().facts().get("recording.integrity"));
      assertEquals("true", result.health().facts().get("evidence.complete"));
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      service.exportEvidence(bytes);
      java.util.Map<String, byte[]> artifacts = new java.util.HashMap<>();
      try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
        var entry = zip.getNextEntry();
        while (entry != null) {
          artifacts.put(entry.getName(), zip.readAllBytes());
          entry = zip.getNextEntry();
        }
      }
      assertTrue(artifacts.containsKey("experiment.manifest"));
      assertTrue(artifacts.containsKey("capture.aarec"));
      assertTrue(artifacts.containsKey("SHA256SUMS"));
      assertTrue(artifacts.containsKey("health.txt"));
      assertEquals(
          ExperimentEvidenceFiles.sha256(artifacts.get("capture.aarec")),
          result.health().facts().get("recording.sha256"),
          "The reported file SHA-256 must cover the complete exported recording, including footer");
      var inspection =
          org.hammer.audio.recording.AudioBlockRecordingReader.inspect(
              directory.resolve("runs").resolve(result.runId()).resolve("capture.aarec"));
      assertEquals(
          inspection.sha256(),
          result.health().facts().get("recording.verifiedContentSha256"),
          "The verified header/block digest has a separate, explicit scope");
    }
  }

  @Test
  void acknowledgementAfterFinalizationIsPersistedWithMatchingExportHashes(@TempDir Path directory)
      throws Exception {
    String property = "audio.recording.storage.warningBytes";
    String previous = System.getProperty(property);
    System.setProperty(property, Long.toString(Long.MAX_VALUE));
    try (var service = service(directory)) {
      var config = configuration(service);
      var ready = service.preflight(config);
      assertTrue(
          ready.health().findings().stream()
              .anyMatch(item -> item.code().equals("storage.margin")));
      service.start(config, ready.token(), true);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15L);
      while (!service.snapshot().exportAvailable() && System.nanoTime() < deadline) {
        Thread.sleep(10L);
      }
      assertTrue(service.snapshot().exportAvailable());
      service.acknowledge("storage.margin", "Reviewed by local operator");
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      service.exportEvidence(bytes);
      java.util.Map<String, byte[]> artifacts = new java.util.HashMap<>();
      try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
        var entry = zip.getNextEntry();
        while (entry != null) {
          artifacts.put(entry.getName(), zip.readAllBytes());
          entry = zip.getNextEntry();
        }
      }
      byte[] health = artifacts.get("health.txt");
      assertTrue(
          new String(health, java.nio.charset.StandardCharsets.UTF_8)
              .contains("Reviewed by local operator"));
      String hashes =
          new String(artifacts.get("SHA256SUMS"), java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(hashes.contains(ExperimentEvidenceFiles.sha256(health) + "  health.txt"));
    } finally {
      if (previous == null) {
        System.clearProperty(property);
      } else {
        System.setProperty(property, previous);
      }
    }
  }

  private static MicrophoneExperimentService service(Path directory) {
    return new MicrophoneExperimentService(
        directory, Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC), List::of);
  }

  private static Configuration configuration(MicrophoneExperimentService service) throws Exception {
    return new Configuration(
        "Example",
        "SIMULATION",
        service.catalogue().profiles().getFirst().id(),
        "single-source",
        "",
        1,
        true,
        "capture.aarec");
  }
}
