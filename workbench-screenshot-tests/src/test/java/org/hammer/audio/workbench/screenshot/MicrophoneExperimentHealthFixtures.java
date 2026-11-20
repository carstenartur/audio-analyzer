package org.hammer.audio.workbench.screenshot;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hammer.audio.acquisition.health.ExperimentHealthAggregator;
import org.hammer.audio.acquisition.health.ExperimentHealthFinding;
import org.hammer.audio.acquisition.health.ExperimentHealthFinding.Severity;
import org.hammer.audio.acquisition.health.ExperimentHealthState;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.RunView;
import org.hammer.audio.experiment.operations.ExperimentOperationsModels.TrackPoint;
import org.hammer.audio.recording.runtime.RecordingState;
import org.hammer.audio.recording.runtime.RecordingStatus;
import org.hammer.audio.recording.runtime.RecordingStorageLevel;
import org.hammer.audio.recording.runtime.RecordingStoragePolicy;
import org.hammer.audio.recording.runtime.RecordingStorageStatus;

/** Typed, fixed-time projections for states that should not depend on a CI disk or microphone. */
final class MicrophoneExperimentHealthFixtures {

  private static final Instant NOW = Instant.parse("2026-11-20T12:00:02Z");
  private static final Path DESTINATION = Path.of("/experiments/run-example/capture.aarec");

  private MicrophoneExperimentHealthFixtures() {}

  static RunView snapshot(ExperimentHealthState state) {
    boolean incomplete = state == ExperimentHealthState.INCOMPLETE;
    boolean degraded = state == ExperimentHealthState.DEGRADED;
    long writtenFrames = incomplete ? 31_744L : 32_000L;
    var storage =
        new RecordingStorageStatus(
            DESTINATION,
            "Experiment storage",
            "ext4",
            true,
            degraded ? 128L * 1024L * 1024L : 8L * 1024L * 1024L * 1024L,
            8L * 1024L * 1024L * 1024L,
            16L * 1024L * 1024L * 1024L,
            writtenFrames * 16L,
            256_313.0,
            256_313.0,
            degraded ? 261L : 33_249L,
            degraded ? RecordingStorageLevel.WARNING : RecordingStorageLevel.NORMAL,
            NOW,
            "",
            new RecordingStoragePolicy(256L << 20, 64L << 20, 300L, 30L, 0.1),
            NOW,
            List.of());
    var recording =
        new RecordingStatus(
            incomplete ? RecordingState.INCOMPLETE : RecordingState.RECORDING,
            DESTINATION,
            NOW.minusSeconds(2L),
            NOW,
            125L,
            incomplete ? 124L : 125L,
            32_000L,
            writtenFrames,
            incomplete ? 1L : 0L,
            incomplete ? 256L : 0L,
            incomplete ? 1L : 0L,
            0,
            64,
            4,
            writtenFrames * 16L,
            256_313.0,
            storage,
            incomplete ? "Recording queue overflow; complete blocks were finalized." : "",
            "");
    var health = new ExperimentHealthAggregator();
    if (degraded) {
      health.update(
          "storage",
          List.of(
              new ExperimentHealthFinding(
                  "storage.margin",
                  Severity.WARNING,
                  "Recording storage margin is low",
                  "261 safe seconds remain. Free storage or stop and finalize before the reserve is"
                      + " reached.",
                  "storage",
                  NOW,
                  10L,
                  "inspect-storage",
                  true,
                  false)),
          NOW);
      health.update(
          "performance",
          List.of(
              new ExperimentHealthFinding(
                  "processing.overruns",
                  Severity.WARNING,
                  "Processing repeatedly exceeded its real-time budget",
                  "32 of 125 blocks exceeded budget. Inspect algorithm settings before a live run.",
                  "performance",
                  NOW,
                  0L,
                  "inspect-diagnostics",
                  true,
                  false)),
          NOW);
    } else if (incomplete) {
      health.update(
          "recording",
          List.of(
              new ExperimentHealthFinding(
                  "recording.incomplete",
                  Severity.ERROR,
                  "Recording contains a dropped block",
                  "256 frames are missing. The original remains incomplete; recovery can preserve"
                      + " readable blocks only.",
                  "recording",
                  NOW,
                  0L,
                  "recover-recording",
                  false,
                  false)),
          NOW);
    }
    var facts =
        Map.ofEntries(
            Map.entry("readiness.scope", "STANDALONE_MICROPHONE"),
            Map.entry("source.format", "16000 Hz / 4 channels / PCM float"),
            Map.entry("source.mode", "SIMULATION"),
            Map.entry("calibration.status", "Not applicable to ideal simulated channels"),
            Map.entry("plugin", "acoustic-localization"),
            Map.entry("plugin.version", "0.1.0"),
            Map.entry("processed.frames", "32000"),
            Map.entry("processed.blocks", "125"),
            Map.entry("elapsed.sampleSeconds", "2"),
            Map.entry("intended.seconds", "5"),
            Map.entry("processing.lastNanos", "8000000"),
            Map.entry("processing.budgetNanos", "16000000"),
            Map.entry("capture.state", incomplete ? "STOPPED" : "RUNNING"),
            Map.entry("capture.lastBlockAt", NOW.toString()),
            Map.entry("evidence.complete", "false"),
            Map.entry(
                "benchmark.status", incomplete ? "Unavailable: experiment ended early" : "Pending"),
            Map.entry("recording.integrity", incomplete ? "INCOMPLETE" : "Pending finalization"));
    return new RunView(
        "run-example",
        health.snapshot(
            "run-example",
            "Microphone-array experiment",
            incomplete ? ExperimentHealthState.INCOMPLETE : ExperimentHealthState.RUNNING,
            incomplete ? "INCOMPLETE EVIDENCE FINALIZED" : "ACQUIRING / LOCALIZING",
            facts,
            NOW),
        recording,
        List.of(new TrackPoint(1, 1.4, 1.2, 0.92)),
        incomplete);
  }
}
