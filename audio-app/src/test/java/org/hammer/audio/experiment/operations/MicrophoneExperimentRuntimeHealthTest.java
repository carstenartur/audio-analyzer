package org.hammer.audio.experiment.operations;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipInputStream;
import org.hammer.audio.acquisition.LocalizationEngine;
import org.hammer.audio.acquisition.LocalizationExperiment;
import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.acquisition.MultiChannelAudioSource;
import org.hammer.audio.acquisition.SynchronizationAssessment;
import org.hammer.audio.acquisition.health.ExperimentHealthFinding;
import org.hammer.audio.acquisition.health.ExperimentHealthSnapshot;
import org.hammer.audio.acquisition.health.ExperimentHealthState;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.Configuration;
import org.hammer.audio.recording.runtime.RecordingState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MicrophoneExperimentRuntimeHealthTest {

  private static final String OVERRUNS = "processing.overruns";
  private static final Set<String> REQUIRED_REPORTS = Set.of("localization.csv", "summary.md");
  private static final Map<String, String> SYNTHETIC_REPORTS =
      Map.of(
          "localization.csv", "frame,track,x,y,confidence\n",
          "summary.md",
              "# Controlled timing fixture\n\nNo localization positions are estimated.\n");
  // Deliberately independent of the simulator's sample rate: the host must use the reported budget.
  private static final long BUDGET_NANOS = 18_575_964L;

  @Test
  void runningOverrunsUseReportedBudgetAndRetainAcknowledgedRecoveryAndRecurrence(
      @TempDir Path directory) throws Exception {
    ControlledEngine engine = new ControlledEngine();
    try (var service =
        new MicrophoneExperimentService(
            directory,
            Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC),
            List::of,
            engine)) {
      try {
        var config =
            new Configuration(
                "Controlled processing budgets",
                "SIMULATION",
                service.catalogue().profiles().getFirst().id(),
                "single-source",
                "",
                2,
                true,
                "capture.aarec");
        service.start(config, service.preflight(config).token(), true);

        engine.awaitBlock(1);
        var withinBudget = service.snapshot().health();
        assertEquals("0", withinBudget.facts().get(OVERRUNS));
        assertEquals(
            Long.toString(BUDGET_NANOS), withinBudget.facts().get("processing.budgetNanos"));
        assertEquals(ExperimentHealthState.RUNNING, withinBudget.state());
        assertFalse(
            withinBudget.findings().stream().anyMatch(item -> item.code().equals(OVERRUNS)));

        engine.resume();
        engine.awaitBlock(4);
        var degraded = service.snapshot();
        assertFalse(degraded.exportAvailable(), "The warning must be observable before finish");
        assertEquals(ExperimentHealthState.DEGRADED, degraded.health().state());
        assertEquals("3", degraded.health().facts().get(OVERRUNS));
        assertTrue(overrun(degraded.health()).detail().startsWith("3 of 4 blocks"));
        assertEquals(1L, warningEvents(degraded.health()));
        service.acknowledge(OVERRUNS, "Reviewed by local operator");

        engine.resume();
        engine.awaitBlock(5);
        var continuing = service.snapshot().health();
        assertEquals("3", continuing.facts().get(OVERRUNS), "Budget equality is not an overrun");
        assertTrue(
            overrun(continuing).acknowledged(), "A new detail must not reset acknowledgement");
        assertTrue(overrun(continuing).detail().startsWith("3 of 5 blocks"));
        assertEquals(
            1L, warningEvents(continuing), "A continuing condition emits one warning event");

        engine.resume();
        engine.awaitBlock(20);
        var recovered = service.snapshot().health();
        assertEquals(ExperimentHealthState.RUNNING, recovered.state());
        assertTrue(
            overrun(recovered).acknowledged(), "Recovered warnings remain available to review");
        assertEquals("3", recovered.facts().get(OVERRUNS));

        engine.resume();
        engine.awaitBlock(21);
        var recurring = service.snapshot();
        assertFalse(recurring.exportAvailable());
        assertEquals(ExperimentHealthState.DEGRADED, recurring.health().state());
        assertEquals("4", recurring.health().facts().get(OVERRUNS));
        assertFalse(
            overrun(recurring.health()).acknowledged(), "A recurrence requires a new review");
        assertEquals(2L, warningEvents(recurring.health()));

        engine.resume();
        await().atMost(10L, TimeUnit.SECONDS).until(() -> service.snapshot().exportAvailable());
        var completed = service.snapshot().health();
        assertEquals(ExperimentHealthState.COMPLETED, completed.state());
        assertEquals("4", completed.facts().get(OVERRUNS));
        assertEquals("32", completed.facts().get("processed.blocks"));
        assertFalse(
            overrun(completed).acknowledged(), "Finish must retain the unreviewed recurrence");
        assertEquals(2L, warningEvents(completed));
        Map<String, byte[]> files = evidenceFiles(service);
        assertEquals(
            SYNTHETIC_REPORTS.get("summary.md"),
            new String(files.get("summary.md"), StandardCharsets.UTF_8));
        String evidence = new String(files.get("health.txt"), StandardCharsets.UTF_8);
        assertTrue(evidence.contains("processing.overruns=4"));
        assertTrue(evidence.contains("Reviewed by local operator"));
        assertTrue(evidence.contains("Processing repeatedly exceeded its real-time budget"));
      } finally {
        engine.releaseAll();
      }
    }
  }

  @Test
  void aMissingDeclaredReportKeepsRecordingAndPartialEvidenceIncomplete(@TempDir Path directory)
      throws Exception {
    assertIncompleteReports(
        directory,
        Set.of("timings.csv", "notes.md"),
        Map.of("notes.md", "# Generated test notes\n"),
        "timings.csv");
  }

  @Test
  void anUndeclaredReportContractCannotClaimCompleteEvidence(@TempDir Path directory)
      throws Exception {
    assertIncompleteReports(
        directory, Set.of(), Map.of("custom-analysis.txt", "Test output\n"), "");
  }

  private static void assertIncompleteReports(
      Path directory, Set<String> required, Map<String, String> reports, String missing)
      throws Exception {
    ControlledEngine engine = new ControlledEngine(required, reports);
    engine.releaseAll();
    try (var service =
        new MicrophoneExperimentService(
            directory,
            Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC),
            List::of,
            engine)) {
      var config =
          new Configuration(
              "Missing output check",
              "SIMULATION",
              service.catalogue().profiles().getFirst().id(),
              "single-source",
              "",
              2,
              true,
              "capture.aarec");
      service.start(config, service.preflight(config).token(), true);
      await().atMost(10L, TimeUnit.SECONDS).until(() -> service.snapshot().exportAvailable());
      var result = service.snapshot();
      assertEquals(ExperimentHealthState.INCOMPLETE, result.health().state());
      assertEquals("false", result.health().facts().get("evidence.complete"));
      assertEquals(
          RecordingState.COMPLETED,
          result.recording().state(),
          "The intact recording must be retained");
      assertEquals(
          String.join(", ", required.stream().sorted().toList()),
          result.health().facts().get("artifacts.required"));
      assertEquals(
          String.join(", ", reports.keySet().stream().sorted().toList()),
          result.health().facts().get("artifacts.generated"));
      assertEquals(missing, result.health().facts().get("artifacts.missing"));
      var finding =
          result.health().findings().stream()
              .filter(item -> item.code().equals("evidence.artifacts"))
              .findFirst()
              .orElseThrow();
      assertEquals(ExperimentHealthFinding.Severity.ERROR, finding.severity());
      assertTrue(finding.message().contains(required.isEmpty() ? "declared" : "missing"));
      Map<String, byte[]> files = evidenceFiles(service);
      assertTrue(files.containsKey("capture.aarec"));
      assertTrue(files.containsKey("experiment.manifest"));
      assertTrue(files.keySet().containsAll(reports.keySet()));
      if (!missing.isEmpty()) {
        assertFalse(files.containsKey(missing));
      }
      String health = new String(files.get("health.txt"), StandardCharsets.UTF_8);
      assertTrue(health.contains("evidence.complete=false"));
      assertTrue(health.contains("evidence.artifacts"));
      String hashes = new String(files.get("SHA256SUMS"), StandardCharsets.UTF_8);
      assertTrue(
          hashes.contains(
              ExperimentEvidenceFiles.sha256(files.get("health.txt")) + "  health.txt"));
    }
  }

  private static ExperimentHealthFinding overrun(ExperimentHealthSnapshot health) {
    return health.findings().stream()
        .filter(item -> item.code().equals(OVERRUNS))
        .findFirst()
        .orElseThrow();
  }

  private static long warningEvents(ExperimentHealthSnapshot health) {
    return health.history().stream()
        .filter(event -> event.code().equals(OVERRUNS) && event.action().equals("WARNING"))
        .count();
  }

  private static Map<String, byte[]> evidenceFiles(MicrophoneExperimentService service)
      throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    service.exportEvidence(bytes);
    Map<String, byte[]> files = new TreeMap<>();
    try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        files.put(entry.getName(), zip.readAllBytes());
      }
    }
    return files;
  }

  /** Keeps actual simulation, recording and evidence; only compute timings are controlled. */
  private static final class ControlledEngine implements LocalizationEngine {
    private final LocalizationEngine simulator =
        ServiceLoader.load(LocalizationEngine.class).findFirst().orElseThrow();
    private final BlockingQueue<Integer> checkpoints = new LinkedBlockingQueue<>();
    private final Semaphore resume = new Semaphore(0);
    private final Set<String> requiredArtifacts;
    private final Map<String, String> reports;

    private ControlledEngine() {
      this(REQUIRED_REPORTS, SYNTHETIC_REPORTS);
    }

    private ControlledEngine(Set<String> requiredArtifacts, Map<String, String> reports) {
      this.requiredArtifacts = Set.copyOf(requiredArtifacts);
      this.reports = Map.copyOf(reports);
    }

    @Override
    public String id() {
      return simulator.id();
    }

    @Override
    public String version() {
      return simulator.version();
    }

    @Override
    public List<String> scenarios() {
      return simulator.scenarios();
    }

    @Override
    public MicrophoneArray exampleArray() {
      return simulator.exampleArray();
    }

    @Override
    public float simulationSampleRate(String scenario) {
      return simulator.simulationSampleRate(scenario);
    }

    @Override
    public MultiChannelAudioSource simulate(
        String scenario, MicrophoneArray array, int durationSeconds) {
      return simulator.simulate(scenario, array, durationSeconds);
    }

    @Override
    public Result run(
        MultiChannelAudioSource source,
        LocalizationExperiment experiment,
        Options options,
        Consumer<Frame> progress)
        throws IOException {
      int blocks = 0;
      long overruns = 0L;
      try (source) {
        while (source.readBlock(options.blockFrames()).isPresent()) {
          boolean overBudget = Set.of(1, 2, 3, 20).contains(blocks);
          long processingNanos = BUDGET_NANOS + (overBudget ? 1L : 0L);
          if (overBudget) {
            overruns++;
          }
          progress.accept(
              new Frame(
                  blocks,
                  processingNanos,
                  BUDGET_NANOS,
                  SynchronizationAssessment.nominalSharedClock(),
                  true,
                  List.of()));
          blocks++;
          if (Set.of(1, 4, 5, 20, 21).contains(blocks)) {
            checkpoints.add(blocks);
            awaitResume();
          }
        }
      }
      return new Result(experiment, blocks, overruns, false, requiredArtifacts, reports);
    }

    private void awaitResume() throws IOException {
      try {
        if (!resume.tryAcquire(10L, TimeUnit.SECONDS)) {
          throw new IOException("Test did not release the processing checkpoint");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted at processing checkpoint", exception);
      }
    }

    private void awaitBlock(int count) throws InterruptedException {
      assertEquals(
          count,
          checkpoints.poll(10L, TimeUnit.SECONDS),
          "Expected controlled progress checkpoint");
    }

    private void resume() {
      resume.release();
    }

    private void releaseAll() {
      resume.release(5);
    }
  }
}
