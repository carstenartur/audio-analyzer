package org.hammer.audio.workflow.collaboration;

/** Transport-independent destination for committed session events. */
@FunctionalInterface
public interface WorkflowSessionEventSink {
  void publish(WorkflowSessionEvent event);

  static WorkflowSessionEventSink noOp() {
    return ignored -> {};
  }
}
