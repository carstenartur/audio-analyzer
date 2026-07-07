package org.hammer.audio.workflow.store;

import java.util.Objects;

/**
 * Summary entry returned by {@link VersionedWorkflowStore#history}.
 *
 * <p>Owned by the persistence facade layer. Application services use this to display a version
 * list; they must not need to understand storage internals.
 *
 * @param commitId identifier of this commit
 * @param metadata author, message and timestamp of this commit
 * @param workflowId identifier of the workflow at this commit
 */
public record CommitInfo(CommitId commitId, CommitMetadata metadata, String workflowId) {

  public CommitInfo {
    Objects.requireNonNull(commitId, "commitId");
    Objects.requireNonNull(metadata, "metadata");
    if (workflowId == null || workflowId.isBlank()) {
      throw new IllegalArgumentException("workflowId must not be blank");
    }
  }
}
