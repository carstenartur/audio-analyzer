package org.hammer.audio.workflow.collaboration.store;

import java.util.Objects;

/**
 * Atomic durable append request for one accepted collaboration command.
 *
 * @param sessionId target collaboration-session identifier
 * @param expectedRevision semantic revision required by the caller
 * @param operation canonically serialized accepted operation
 * @param workflowId identifier of the resulting canonical workflow
 * @param workflowDsl deterministic serialized resulting workflow
 * @param outboxEvent event to persist atomically with the operation
 */
public record WorkflowSessionAppendCommand(
    String sessionId,
    long expectedRevision,
    WorkflowOperationPersistenceData operation,
    String workflowId,
    String workflowDsl,
    WorkflowOutboxEventData outboxEvent) {

  public WorkflowSessionAppendCommand {
    sessionId = requireNotBlank(sessionId, "sessionId");
    if (expectedRevision < 0) {
      throw new IllegalArgumentException("expectedRevision must be >= 0");
    }
    Objects.requireNonNull(operation, "operation");
    workflowId = requireNotBlank(workflowId, "workflowId");
    workflowDsl = requireNotBlank(workflowDsl, "workflowDsl");
    Objects.requireNonNull(outboxEvent, "outboxEvent");
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
