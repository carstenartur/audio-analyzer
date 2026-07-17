package org.hammer.audio.workflow.collaboration.store;

import java.util.Objects;

/** Result of an atomic collaboration append, including idempotent duplicate detection. */
public record WorkflowSessionAppendResult(
    StoredWorkflowSession session,
    StoredWorkflowOperation operation,
    PendingWorkflowOutboxEntry outboxEntry,
    boolean duplicate) {

  public WorkflowSessionAppendResult {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(outboxEntry, "outboxEntry");
  }
}
