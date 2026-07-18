package org.hammer.audio.workflow.collaboration.retention;

import java.util.List;
import java.util.Objects;

/**
 * Persistence-neutral result of selecting one bounded set of published outbox rows.
 *
 * @param scannedCount number of published rows considered by the selection query
 * @param candidates eligible rows in deterministic deletion order
 */
public record WorkflowOutboxRetentionSelection(
    long scannedCount, List<WorkflowOutboxRetentionCandidate> candidates) {

  public WorkflowOutboxRetentionSelection {
    if (scannedCount < 0) {
      throw new IllegalArgumentException("scannedCount must be >= 0");
    }
    candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
    if (scannedCount < candidates.size()) {
      throw new IllegalArgumentException("scannedCount must be >= candidate count");
    }
  }
}
