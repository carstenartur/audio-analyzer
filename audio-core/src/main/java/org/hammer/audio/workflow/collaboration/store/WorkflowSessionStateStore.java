package org.hammer.audio.workflow.collaboration.store;

import java.util.List;
import java.util.Optional;

/** Framework-independent durable collaboration-session persistence boundary. */
public interface WorkflowSessionStateStore {

  /** Creates a durable session aggregate. */
  StoredWorkflowSession create(StoredWorkflowSession session);

  /** Finds a durable session by its stable identifier. */
  Optional<StoredWorkflowSession> find(String sessionId);

  /** Returns all open durable sessions in stable identifier order. */
  List<StoredWorkflowSession> openSessions();

  /** Atomically appends one accepted operation, aggregate update and outbox event. */
  WorkflowSessionAppendResult append(WorkflowSessionAppendCommand command);

  /**
   * Reserves the next durable collaboration-event sequence without changing semantic revision.
   *
   * <p>Lifecycle and presence payloads remain transient, but advancing their sequence prevents SSE
   * identifiers from moving backwards after a process restart.
   */
  StoredWorkflowSession advanceEventSequence(String sessionId, long expectedSequence);

  /** Atomically closes a session and reserves its final lifecycle-event sequence. */
  StoredWorkflowSession close(
      String sessionId, long expectedRevision, long expectedSequence);

  /** Returns accepted operations in stable session event-sequence order. */
  List<StoredWorkflowOperation> operations(String sessionId);

  /** Returns unpublished outbox entries in stable event order. */
  List<StoredWorkflowOutboxEntry> pendingOutbox(int limit);
}
