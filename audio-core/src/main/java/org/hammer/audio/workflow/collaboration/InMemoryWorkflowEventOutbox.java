package org.hammer.audio.workflow.collaboration;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** In-memory test/dev outbox implementation. */
public final class InMemoryWorkflowEventOutbox implements WorkflowEventOutbox {

  private final Map<String, OutboxEntry> entries = new LinkedHashMap<>();

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
    Iterator<Map.Entry<String, OutboxEntry>> iterator = entries.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<String, OutboxEntry> entry = iterator.next();
      if (entry.getKey().equals(entryId)) {
        iterator.remove();
        return;
      }
    }
  }
}
