package org.hammer.audio.workflow.collaboration;

/** Abstraction for publishing committed workflow collaboration events to transports. */
@FunctionalInterface
public interface WorkflowEventBus {
  void publish(WorkflowCollaborationEvent event);
}
