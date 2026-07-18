package org.hammer.audio.workflow.collaboration.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowOutboxRetentionServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");

  @Test
  void planUsesOneInjectedClockInstantAndAConservativeCutoff() {
    Instant publishedAt = NOW.minus(Duration.ofDays(31));
    CapturingStore store =
        new CapturingStore(
            new WorkflowOutboxRetentionSelection(
                3,
                List.of(
                    new WorkflowOutboxRetentionCandidate(
                        "event.old",
                        "session.old",
                        publishedAt,
                        WorkflowOutboxRetentionReason.PUBLISHED_AT_OR_BEFORE_CUTOFF))));
    WorkflowOutboxRetentionSettings settings =
        new WorkflowOutboxRetentionSettings(Duration.ofDays(30), 25);
    WorkflowOutboxRetentionService service =
        new WorkflowOutboxRetentionService(
            store, Clock.fixed(NOW, ZoneOffset.UTC), settings);

    WorkflowOutboxRetentionPlan plan = service.plan();

    assertEquals(NOW, plan.plannedAt());
    assertEquals(NOW.minus(Duration.ofDays(30)), plan.publishedCutoff());
    assertEquals(25, plan.batchSize());
    assertEquals(3, plan.scannedCount());
    assertEquals(List.of("event.old"), plan.candidateEventIds());
    assertEquals(
        Map.of(WorkflowOutboxRetentionReason.PUBLISHED_AT_OR_BEFORE_CUTOFF, 1L),
        plan.reasonCounts());
    assertEquals(plan.publishedCutoff(), store.cutoff);
    assertEquals(25, store.limit);
  }

  @Test
  void deleteDelegatesTheExactImmutablePlan() {
    CapturingStore store = new CapturingStore(new WorkflowOutboxRetentionSelection(0, List.of()));
    WorkflowOutboxRetentionService service =
        new WorkflowOutboxRetentionService(
            store,
            Clock.fixed(NOW, ZoneOffset.UTC),
            WorkflowOutboxRetentionSettings.conservativeDefaults());
    WorkflowOutboxRetentionPlan plan = service.plan();

    WorkflowOutboxRetentionDeletionResult result = service.delete(plan);

    assertEquals(plan, store.deletedPlan);
    assertEquals(List.of(), result.deletedEventIds());
  }

  @Test
  void destructiveConfigurationRequiresPositiveBoundedValues() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorkflowOutboxRetentionSettings(Duration.ZERO, 100));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WorkflowOutboxRetentionSettings(
                Duration.ofDays(1), WorkflowOutboxRetentionSettings.MAXIMUM_BATCH_SIZE + 1));
  }

  @Test
  void blankModeIsReportOnlyAndDeleteMustBeExplicit() {
    assertEquals(WorkflowOutboxRetentionMode.REPORT_ONLY, WorkflowOutboxRetentionMode.parse(null));
    assertEquals(WorkflowOutboxRetentionMode.REPORT_ONLY, WorkflowOutboxRetentionMode.parse("dry-run"));
    assertEquals(WorkflowOutboxRetentionMode.DELETE, WorkflowOutboxRetentionMode.parse("delete"));
    assertThrows(
        IllegalArgumentException.class, () -> WorkflowOutboxRetentionMode.parse("automatic"));
  }

  private static final class CapturingStore implements WorkflowOutboxRetentionStore {

    private final WorkflowOutboxRetentionSelection selection;
    private Instant cutoff;
    private int limit;
    private WorkflowOutboxRetentionPlan deletedPlan;

    private CapturingStore(WorkflowOutboxRetentionSelection selection) {
      this.selection = selection;
    }

    @Override
    public WorkflowOutboxRetentionSelection selectPublishedBefore(
        Instant publishedCutoff, int candidateLimit) {
      cutoff = publishedCutoff;
      limit = candidateLimit;
      return selection;
    }

    @Override
    public WorkflowOutboxRetentionDeletionResult deletePublished(
        WorkflowOutboxRetentionPlan plan) {
      deletedPlan = plan;
      return new WorkflowOutboxRetentionDeletionResult(List.of(), plan.candidateEventIds());
    }
  }
}
