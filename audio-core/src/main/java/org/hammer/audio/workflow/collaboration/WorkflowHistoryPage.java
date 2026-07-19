package org.hammer.audio.workflow.collaboration;

import java.util.List;
import java.util.Objects;

/**
 * Stable newest-first page of immutable semantic history.
 *
 * <p>Passing {@link #nextBeforeRevision()} as the next request cursor cannot duplicate the final
 * entry from this page because the cursor is exclusive.
 *
 * @param operations history entries ordered by descending semantic revision
 * @param nextBeforeRevision exclusive revision cursor for the next older page, or {@code null}
 * @param currentRevision current session revision at query time
 */
public record WorkflowHistoryPage(
    List<WorkflowHistoryDescriptor> operations, Long nextBeforeRevision, long currentRevision) {

  public WorkflowHistoryPage {
    operations = List.copyOf(Objects.requireNonNull(operations, "operations"));
    if (nextBeforeRevision != null && nextBeforeRevision <= 0) {
      throw new IllegalArgumentException("nextBeforeRevision must be > 0");
    }
    if (currentRevision < 0) {
      throw new IllegalArgumentException("currentRevision must be >= 0");
    }
  }
}
