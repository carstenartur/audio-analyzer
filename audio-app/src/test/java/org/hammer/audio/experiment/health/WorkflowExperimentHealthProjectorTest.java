package org.hammer.audio.experiment.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hammer.audio.acquisition.health.ExperimentHealthState;
import org.hammer.audio.workflow.execution.ExecutionStatus;
import org.hammer.audio.workflow.execution.WorkflowRunModels.SourceKind;
import org.hammer.audio.workflow.execution.http.WorkflowRunApiModels.RunResponse;
import org.hammer.audio.workflow.execution.http.WorkflowRunApiModels.RunResultResponse;
import org.hammer.audio.workflow.execution.http.WorkflowRunApiModels.RunSourceResponse;
import org.junit.jupiter.api.Test;

class WorkflowExperimentHealthProjectorTest {
  private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

  @Test
  void completedComputationWithMissingOutputsRemainsIncomplete() {
    var run = run("COMPLETED", "COMPUTATION");
    var health = WorkflowExperimentHealthProjector.runtime(run, result(run, Map.of()), NOW);
    assertEquals(ExperimentHealthState.INCOMPLETE, health.state());
    assertEquals("false", health.facts().get("evidence.complete"));
    assertTrue(
        health.findings().stream()
            .anyMatch(item -> item.code().equals("workflow.evidence-incomplete")));
  }

  @Test
  void backendMetadataWithoutOutputDigestDoesNotProveAudioEvidence() {
    var run = run("COMPLETED", "COMPUTATION");
    var health =
        WorkflowExperimentHealthProjector.runtime(
            run, result(run, Map.of("backendVersion", "deterministic-audio/1")), NOW);
    assertEquals(ExperimentHealthState.INCOMPLETE, health.state());
    assertEquals("false", health.facts().get("evidence.complete"));
  }

  @Test
  void realWorkflowResultRetainsHashAndExactSourceWithoutClaimingDocumentExecution() {
    var run = run("COMPLETED", "COMPUTATION");
    var health =
        WorkflowExperimentHealthProjector.runtime(
            run,
            result(
                run,
                Map.of(
                    "output.digest.sha256",
                    "b".repeat(64),
                    "backendVersion",
                    "deterministic-audio/1")),
            NOW);
    assertEquals(ExperimentHealthState.COMPLETED, health.state());
    assertEquals("7", health.facts().get("workflow.revision"));
    assertEquals("a".repeat(64), health.facts().get("workflow.sha256"));
    assertEquals("WORKFLOW_COMPUTATION_ONLY", health.facts().get("evidence.scope"));
    assertTrue(health.facts().containsKey("artifact.output.digest.sha256.sha256"));
    assertFalse(health.startAllowed());
  }

  @Test
  void lifecycleSimulationDoesNotBecomeAudioEvidence() {
    var run = run("COMPLETED", "SIMULATION");
    var health =
        WorkflowExperimentHealthProjector.runtime(
            run, result(run, Map.of("backendMode", "SIMULATION")), NOW);
    assertEquals(ExperimentHealthState.INCOMPLETE, health.state());
    assertEquals("false", health.facts().get("evidence.complete"));
  }

  private static RunResponse run(String state, String mode) {
    return new RunResponse(
        "run.test",
        "command.test",
        state,
        mode,
        new RunSourceResponse(SourceKind.LIVE_SESSION, "session.test", 7L, null),
        "workflow.test",
        "snapshot.test",
        "plan.test",
        "a".repeat(64),
        NOW,
        NOW,
        NOW,
        100,
        "Finished",
        List.of());
  }

  private static RunResultResponse result(RunResponse run, Map<String, String> artifacts) {
    return new RunResultResponse(
        run, "COMPLETED", Map.of("output", ExecutionStatus.COMPLETED), NOW, NOW, null, artifacts);
  }
}
