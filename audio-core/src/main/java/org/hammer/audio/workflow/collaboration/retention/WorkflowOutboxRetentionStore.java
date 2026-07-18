package org.hammer.audio.workflow.collaboration.retention;

import java.time.Instant;

/** Persistence boundary for previewing and safely deleting published outbox rows. */
public interface WorkflowOutboxRetentionStore {

  /**
   * Selects at most {@code limit} eligible published rows in deterministic order.
   *
   * <p>This method is a read-only preview. Implementations must exclude pending, failed, leased and
   * otherwise uncertain rows.
   */
  WorkflowOutboxRetentionSelection selectPublishedBefore(Instant publishedCutoff, int limit);

  /**
   * Revalidates every candidate under a write lock and deletes only rows that remain eligible.
   *
   * <p>Missing or changed rows are reported as skipped so repeated or competing cleanup runs are
   * idempotent.
   */
  WorkflowOutboxRetentionDeletionResult deletePublished(WorkflowOutboxRetentionPlan plan);
}
