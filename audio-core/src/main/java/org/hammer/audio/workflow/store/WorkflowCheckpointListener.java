package org.hammer.audio.workflow.store;

/** Application hook invoked after a durable workflow checkpoint was created. */
@FunctionalInterface
public interface WorkflowCheckpointListener {

  void checkpointCreated(
      String branch, CommitId commitId, WorkflowSnapshot snapshot, CommitMetadata metadata);

  static WorkflowCheckpointListener noOp() {
    return (branch, commitId, snapshot, metadata) -> {};
  }
}
