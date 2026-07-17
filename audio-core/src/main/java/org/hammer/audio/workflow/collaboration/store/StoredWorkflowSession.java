package org.hammer.audio.workflow.collaboration.store;

import java.time.Instant;
import java.util.Objects;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;

/**
 * Immutable durable representation of a collaboration-session aggregate.
 *
 * @param sessionId stable collaboration-session identifier
 * @param mode immutable collaboration mode
 * @param owner owner actor metadata
 * @param createdAt session creation timestamp
 * @param workflowId identifier of the canonical workflow
 * @param workflowDsl deterministic serialized canonical workflow
 * @param revision current semantic revision
 * @param sequence current durable event sequence
 * @param closed whether the session has been explicitly closed
 */
public record StoredWorkflowSession(
    String sessionId,
    CollaborationMode mode,
    OperationActor owner,
    Instant createdAt,
    String workflowId,
    String workflowDsl,
    long revision,
    long sequence,
    boolean closed) {

  public StoredWorkflowSession {
    sessionId = requireNotBlank(sessionId, "sessionId");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(createdAt, "createdAt");
    workflowId = requireNotBlank(workflowId, "workflowId");
    workflowDsl = requireNotBlank(workflowDsl, "workflowDsl");
    requireNonNegative(revision, "revision");
    requireNonNegative(sequence, "sequence");
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static void requireNonNegative(long value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must be >= 0");
    }
  }
}
