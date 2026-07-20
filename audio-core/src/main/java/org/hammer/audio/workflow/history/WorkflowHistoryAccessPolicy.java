package org.hammer.audio.workflow.history;

/** Application policy invoked before explicit workflow-history commands access or mutate state. */
@FunctionalInterface
public interface WorkflowHistoryAccessPolicy {

  /**
   * Rejects a restore when current application/session state makes it unsafe.
   *
   * @param branch branch targeted by the restore
   * @param workflowId workflow restored by the command
   */
  void assertRestoreAllowed(String branch, String workflowId);

  /**
   * Allows read-only comparisons by default.
   *
   * @param branch branch used as reachability boundary
   * @param workflowId workflow compared by the command
   */
  default void assertCompareAllowed(String branch, String workflowId) {
    // Read-only comparison does not mutate editor, branch or session state.
  }

  /**
   * Returns a policy suitable for non-collaborative and isolated tests.
   *
   * @return policy that permits every compare and restore command
   */
  static WorkflowHistoryAccessPolicy allowAll() {
    return (branch, workflowId) -> { };
  }
}
