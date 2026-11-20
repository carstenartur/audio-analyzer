package org.hammer.audio.acquisition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hammer.audio.acquisition.health.ExperimentHealthAggregator;
import org.hammer.audio.acquisition.health.ExperimentHealthFinding;
import org.hammer.audio.acquisition.health.ExperimentHealthState;
import org.junit.jupiter.api.Test;

class ExperimentHealthTest {

  private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

  @Test
  void blockerCannotBeAcknowledgedAndPreventsStart() {
    ExperimentHealthAggregator health = new ExperimentHealthAggregator();
    health.update(
        "source", List.of(finding("no-device", ExperimentHealthFinding.Severity.BLOCKING)), NOW);
    assertEquals(
        ExperimentHealthState.BLOCKED,
        health
            .snapshot("id", "Example", ExperimentHealthState.READY, "DEFINED", Map.of(), NOW)
            .state());
    assertThrows(
        IllegalArgumentException.class, () -> health.acknowledge("no-device", "operator", NOW));
  }

  @Test
  void warningSurvivesHealthyFrameUntilAcknowledged() {
    ExperimentHealthAggregator health = new ExperimentHealthAggregator();
    health.update(
        "source", List.of(finding("timing", ExperimentHealthFinding.Severity.WARNING)), NOW);
    health.update("source", List.of(), NOW.plusSeconds(1));
    assertEquals(
        ExperimentHealthState.DEGRADED,
        health
            .snapshot("id", "Example", ExperimentHealthState.RUNNING, "LOCALIZING", Map.of(), NOW)
            .state());
    health.acknowledge("timing", "operator", NOW.plusSeconds(2));
    var snapshot =
        health.snapshot(
            "id", "Example", ExperimentHealthState.RUNNING, "LOCALIZING", Map.of(), NOW);
    assertEquals(ExperimentHealthState.RUNNING, snapshot.state());
    assertTrue(
        snapshot.history().stream().anyMatch(event -> event.action().equals("ACKNOWLEDGED")));
  }

  @Test
  void acknowledgedActiveWarningStaysAcknowledgedUntilItRecurs() {
    ExperimentHealthAggregator health = new ExperimentHealthAggregator();
    var warning = finding("timing", ExperimentHealthFinding.Severity.WARNING);
    health.update("source", List.of(warning), NOW);
    health.acknowledge("timing", "operator", NOW);
    health.update("source", List.of(warning), NOW.plusSeconds(1));
    assertTrue(
        health
            .snapshot("id", "Example", ExperimentHealthState.RUNNING, "LOCALIZING", Map.of(), NOW)
            .findings()
            .getFirst()
            .acknowledged());
    health.update("source", List.of(), NOW.plusSeconds(2));
    health.update("source", List.of(warning), NOW.plusSeconds(3));
    assertFalse(
        health
            .snapshot("id", "Example", ExperimentHealthState.RUNNING, "LOCALIZING", Map.of(), NOW)
            .findings()
            .getFirst()
            .acknowledged());
  }

  @Test
  void staleSourceAndIncompleteEvidenceRemainVisible() {
    ExperimentHealthAggregator health = new ExperimentHealthAggregator();
    health.update(
        "source", List.of(finding("last-block", ExperimentHealthFinding.Severity.INFO)), NOW);
    var stale =
        health.snapshot(
            "id",
            "Example",
            ExperimentHealthState.RUNNING,
            "LOCALIZING",
            Map.of(),
            NOW.plusSeconds(11));
    assertTrue(stale.findings().stream().anyMatch(item -> item.code().equals("last-block.stale")));
    assertEquals(
        ExperimentHealthState.INCOMPLETE,
        health
            .snapshot(
                "id",
                "Example",
                ExperimentHealthState.COMPLETED,
                "EXPORTED",
                Map.of("evidence.complete", "false"),
                NOW)
            .state());
  }

  @Test
  void everyLifecycleIsRepresentableAndOnlyReadyAllowsStart() {
    ExperimentHealthAggregator health = new ExperimentHealthAggregator();
    for (ExperimentHealthState state : ExperimentHealthState.values()) {
      var snapshot = health.snapshot("id", "Example", state, "DEFINED", Map.of(), NOW);
      assertEquals(state, snapshot.state());
      if (state == ExperimentHealthState.READY
          || state == ExperimentHealthState.READY_WITH_WARNINGS) {
        assertTrue(snapshot.startAllowed());
      } else {
        assertFalse(snapshot.startAllowed());
      }
    }
  }

  private static ExperimentHealthFinding finding(
      String code, ExperimentHealthFinding.Severity severity) {
    return new ExperimentHealthFinding(
        code, severity, code, "Diagnostic", "source", NOW, 10L, "select-source", true, false);
  }
}
