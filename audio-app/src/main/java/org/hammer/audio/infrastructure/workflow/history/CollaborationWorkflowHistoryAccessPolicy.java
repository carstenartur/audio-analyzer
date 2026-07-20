package org.hammer.audio.infrastructure.workflow.history;

import java.util.Objects;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.history.WorkflowHistoryAccessException;
import org.hammer.audio.workflow.history.WorkflowHistoryAccessPolicy;

/** Blocks branch restoration while a collaboration session actively uses the same workflow. */
public final class CollaborationWorkflowHistoryAccessPolicy implements WorkflowHistoryAccessPolicy {

  private final WorkflowSessionRegistry sessionRegistry;

  /** Creates a restore policy backed by current server-authoritative session membership. */
  public CollaborationWorkflowHistoryAccessPolicy(WorkflowSessionRegistry sessionRegistry) {
    this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
  }

  @Override
  public void assertRestoreAllowed(String branch, String workflowId) {
    boolean active =
        sessionRegistry.sessions().stream()
            .anyMatch(
                session ->
                    session.workflowId().equals(workflowId) && !session.participants().isEmpty());
    if (active) {
      throw new WorkflowHistoryAccessException(
          branch,
          workflowId,
          "Cannot restore workflow "
              + workflowId
              + " while a collaboration session still has joined participants");
    }
  }
}
