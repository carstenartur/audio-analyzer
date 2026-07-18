package org.hammer.audio.app.outbox;

import java.util.Objects;
import org.hammer.audio.workflow.collaboration.outbox.WorkflowOutboxDispatcher;
import org.springframework.scheduling.annotation.Scheduled;

/** Spring scheduling adapter for the transport-neutral durable outbox dispatcher. */
public final class ScheduledWorkflowOutboxDispatcher {

  private final WorkflowOutboxDispatcher dispatcher;

  /** Creates a scheduled adapter around one configured dispatcher instance. */
  public ScheduledWorkflowOutboxDispatcher(WorkflowOutboxDispatcher dispatcher) {
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
  }

  /** Dispatches one bounded batch after each configured fixed delay. */
  @Scheduled(fixedDelayString = "${workbench.collaboration.outbox.poll-interval-ms:1000}")
  public void dispatchDue() {
    dispatcher.dispatchBatch();
  }
}
