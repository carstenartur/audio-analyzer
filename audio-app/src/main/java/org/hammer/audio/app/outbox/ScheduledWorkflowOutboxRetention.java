package org.hammer.audio.app.outbox;

import java.util.Objects;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionDeletionResult;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionMode;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionPlan;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Scheduled operational adapter for report-only or destructive published-outbox retention. */
public final class ScheduledWorkflowOutboxRetention {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(ScheduledWorkflowOutboxRetention.class);

  private final WorkflowOutboxRetentionService service;
  private final WorkflowOutboxRetentionMode mode;

  /** Creates a scheduled adapter with an explicit immutable operational mode. */
  public ScheduledWorkflowOutboxRetention(
      WorkflowOutboxRetentionService service, WorkflowOutboxRetentionMode mode) {
    this.service = Objects.requireNonNull(service, "service");
    this.mode = Objects.requireNonNull(mode, "mode");
  }

  /** Creates one plan and either reports it or revalidates and deletes its candidates. */
  @Scheduled(
      fixedDelayString =
          "${workbench.collaboration.outbox.retention.interval-ms:3600000}")
  public void runRetention() {
    WorkflowOutboxRetentionPlan plan = service.plan();
    if (mode == WorkflowOutboxRetentionMode.REPORT_ONLY) {
      logReport(plan, 0, 0, 0);
      return;
    }
    try {
      WorkflowOutboxRetentionDeletionResult result = service.delete(plan);
      logReport(plan, result.deletedCount(), result.skippedCount(), 0);
    } catch (RuntimeException failure) {
      LOGGER.error(
          "Workflow outbox retention failed: mode={}, plannedAt={}, cutoff={}, scanned={}, "
              + "eligible={}, deleted=0, skipped=0, failed={}",
          mode,
          plan.plannedAt(),
          plan.publishedCutoff(),
          plan.scannedCount(),
          plan.eligibleCount(),
          plan.eligibleCount(),
          failure);
    }
  }

  private void logReport(
      WorkflowOutboxRetentionPlan plan, int deletedCount, int skippedCount, int failedCount) {
    LOGGER.info(
        "Workflow outbox retention: mode={}, plannedAt={}, cutoff={}, scanned={}, eligible={}, "
            + "deleted={}, skipped={}, failed={}, oldest={}, newest={}",
        mode,
        plan.plannedAt(),
        plan.publishedCutoff(),
        plan.scannedCount(),
        plan.eligibleCount(),
        deletedCount,
        skippedCount,
        failedCount,
        plan.oldestPublishedAt().orElse(null),
        plan.newestPublishedAt().orElse(null));
  }
}
