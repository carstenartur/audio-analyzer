package org.hammer.audio.workflow.collaboration.retention;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Result of revalidating and deleting one immutable retention plan.
 *
 * @param deletedEventIds event identifiers deleted in the plan's deterministic order
 * @param skippedEventIds missing or no-longer-eligible event identifiers
 */
public record WorkflowOutboxRetentionDeletionResult(
    List<String> deletedEventIds, List<String> skippedEventIds) {

  public WorkflowOutboxRetentionDeletionResult {
    // Keep the result immutable and prevent contradictory audit output.
    deletedEventIds = validatedIds(deletedEventIds, "deletedEventIds");
    skippedEventIds = validatedIds(skippedEventIds, "skippedEventIds");
    Set<String> overlap = new HashSet<>(deletedEventIds);
    overlap.retainAll(skippedEventIds);
    if (!overlap.isEmpty()) {
      throw new IllegalArgumentException("deleted and skipped event ids overlap: " + overlap);
    }
  }

  /** Number of rows deleted by the transaction. */
  public int deletedCount() {
    return deletedEventIds.size();
  }

  /** Number of planned rows skipped after transaction-time revalidation. */
  public int skippedCount() {
    return skippedEventIds.size();
  }

  private static List<String> validatedIds(List<String> values, String name) {
    List<String> ids = List.copyOf(Objects.requireNonNull(values, name));
    Set<String> unique = new HashSet<>();
    for (String id : ids) {
      Objects.requireNonNull(id, name + " entry");
      if (id.isBlank()) {
        throw new IllegalArgumentException(name + " entries must not be blank");
      }
      if (!unique.add(id)) {
        throw new IllegalArgumentException(name + " contains duplicate id: " + id);
      }
    }
    return ids;
  }
}
