package org.hammer.audio.workflow.collaboration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory test/dev outbox implementation. */
public final class InMemoryWorkflowEventOutbox implements WorkflowEventOutbox {

  private final Map<String, OutboxEntry> entries = new ConcurrentHashMap<>();

  @Override
  public OutboxEntry append(WorkflowCollaborationEvent event) {
    Objects.requireNonNull(event, "event");
    OutboxEntry entry = new OutboxEntry(UUID.randomUUID().toString(), event);
    entries.put(entry.entryId(), entry);
    return entry;
  }

  @Override
  public List<OutboxEntry> pending() {
    return new ArrayList<>(entries.values());
  }

  @Override
  public void markPublished(String entryId) {
    Objects.requireNonNull(entryId, "entryId");
    entries.remove(entryId);
  }
}
