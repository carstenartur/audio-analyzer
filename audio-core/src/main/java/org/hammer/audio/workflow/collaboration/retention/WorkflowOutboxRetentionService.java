package org.hammer.audio.workflow.collaboration.retention;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Creates deterministic retention previews and executes them through one persistence boundary. */
public final class WorkflowOutboxRetentionService {

  private final WorkflowOutboxRetentionStore store;
  private final Clock clock;
  private final WorkflowOutboxRetentionSettings settings;

  /** Creates a service with injectable time and conservative validated settings. */
  public WorkflowOutboxRetentionService(
      WorkflowOutboxRetentionStore store,
      Clock clock,
      WorkflowOutboxRetentionSettings settings) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  /** Captures one immutable dry-run plan at the current injected-clock instant. */
  public WorkflowOutboxRetentionPlan plan() {
    Instant plannedAt = clock.instant();
    Instant cutoff = settings.publishedCutoffAt(plannedAt);
    WorkflowOutboxRetentionSelection selection =
        store.selectPublishedBefore(cutoff, settings.batchSize());
    return new WorkflowOutboxRetentionPlan(
        plannedAt,
        cutoff,
        settings.batchSize(),
        selection.scannedCount(),
        selection.candidates());
  }

  /** Revalidates and executes a previously captured immutable plan. */
  public WorkflowOutboxRetentionDeletionResult delete(WorkflowOutboxRetentionPlan plan) {
    return store.deletePublished(Objects.requireNonNull(plan, "plan"));
  }

  /** Returns the immutable settings used for every plan. */
  public WorkflowOutboxRetentionSettings settings() {
    return settings;
  }
}
