package org.hammer.audio.workflow.collaboration.outbox;

/** Transport adapter for publishing committed workflow collaboration events. */
@FunctionalInterface
public interface WorkflowOutboxPublisher {

  /** Publishes one event using {@link WorkflowOutboxMessage#eventId()} for idempotency. */
  void publish(WorkflowOutboxMessage message);
}
