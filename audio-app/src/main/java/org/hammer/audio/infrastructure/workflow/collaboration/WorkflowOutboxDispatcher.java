package org.hammer.audio.infrastructure.workflow.collaboration;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.hammer.audio.workflow.collaboration.WorkflowSessionEventSink;
import org.springframework.scheduling.annotation.Scheduled;

/** Independent retrying dispatcher for committed JDBC outbox rows. */
public final class WorkflowOutboxDispatcher {

  private static final Logger LOG = Logger.getLogger(WorkflowOutboxDispatcher.class.getName());
  private final JdbcWorkflowSessionStateStore store;
  private final WorkflowSessionEventSink eventSink;

  public WorkflowOutboxDispatcher(
      JdbcWorkflowSessionStateStore store, WorkflowSessionEventSink eventSink) {
    this.store = Objects.requireNonNull(store, "store");
    this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
  }

  @Scheduled(fixedDelayString = "${workbench.collaboration.outbox.delay-ms:100}")
  public void dispatch() {
    for (JdbcWorkflowSessionStateStore.OutboxRow row : store.pendingOutbox(100)) {
      try {
        eventSink.publish(row.event());
        store.markPublished(row.eventId());
      } catch (RuntimeException ex) {
        store.markAttempt(row.eventId());
        LOG.log(Level.WARNING, "Cannot dispatch workflow outbox event " + row.eventId(), ex);
        break;
      }
    }
  }
}
