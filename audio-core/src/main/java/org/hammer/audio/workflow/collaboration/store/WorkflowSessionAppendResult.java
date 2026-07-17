package org.hammer.audio.workflow.collaboration.store;

import java.util.Objects;

/**
 * Result of an atomic collaboration append, including idempotent duplicate detection.
 *
 * @param session durable session state after the command
 * @param operation durable accepted operation
 * @param outboxEntry durable outbox entry paired with the operation
 * @param duplicate whether the command had already been accepted identically
 */
public record WorkflowSessionAppendResult(
    StoredWorkflowSession session,
    StoredWorkflowOperation operation,
    StoredWorkflowOutboxEntry outboxEntry,
    boolean duplicate) {

  public WorkflowSessionAppendResult {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(outboxEntry, "outboxEntry");
  }
}
