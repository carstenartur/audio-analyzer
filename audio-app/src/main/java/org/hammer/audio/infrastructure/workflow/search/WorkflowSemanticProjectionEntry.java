package org.hammer.audio.infrastructure.workflow.search;

import java.util.Objects;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.WorkflowSnapshot;

/** One branch position and authoritative snapshot supplied to the semantic projector. */
record WorkflowSemanticProjectionEntry(
    CommitId commitId, int branchPosition, WorkflowSnapshot snapshot) {

  WorkflowSemanticProjectionEntry {
    Objects.requireNonNull(commitId, "commitId");
    Objects.requireNonNull(snapshot, "snapshot");
    if (branchPosition < 0) {
      throw new IllegalArgumentException("branchPosition must be >= 0");
    }
  }
}
