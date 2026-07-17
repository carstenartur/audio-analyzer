package org.hammer.audio.workflow.collaboration.store;

import java.util.List;
import java.util.Optional;

/** Framework-independent durable collaboration-session persistence boundary. */
public interface WorkflowSessionStateStore {

  /** Creates a durable session aggregate. */
  StoredWorkflowSession create(StoredWorkflowSession session);

  /** Finds a durable session by its stable identifier. */
  Optional<StoredWorkflowSession> find(String sessionId);

  /** Atomically appends one accepted operation, aggregate update and outbox event. */
  WorkflowSessionAppendResult append(WorkflowSessionAppendCommand command);

  /** Returns accepted operations in stable session sequence order. */
  List<StoredWorkflowOperation> operations(String sessionId);

  /** Returns unpublished outbox entries in stable event order. */
  List<StoredWorkflowOutboxEntry> pendingOutbox(int limit);
}
