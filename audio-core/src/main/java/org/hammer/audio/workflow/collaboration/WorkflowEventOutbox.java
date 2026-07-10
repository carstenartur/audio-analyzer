package org.hammer.audio.workflow.collaboration;

import java.util.List;
import java.util.Objects;

/** Transactional outbox abstraction for committed collaboration events. */
public interface WorkflowEventOutbox {

  OutboxEntry append(WorkflowCollaborationEvent event);

  List<OutboxEntry> pending();

  void markPublished(String entryId);

  /**
   * Stored outbox message.
   *
   * @param entryId unique outbox entry identifier
   * @param event committed event payload
   */
  record OutboxEntry(String entryId, WorkflowCollaborationEvent event) {
    public OutboxEntry {
      Objects.requireNonNull(entryId, "entryId");
      Objects.requireNonNull(event, "event");
      if (entryId.isBlank()) {
        throw new IllegalArgumentException("entryId must not be blank");
      }
    }
  }
}
