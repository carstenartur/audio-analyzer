package org.hammer.audio.workflow.execution;

import java.util.Objects;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitInfo;

/**
 * Immutable reproducibility evidence bundle produced by a completed workflow run.
 *
 * <p>A bundle ties together the frozen workflow snapshot that was executed, the terminal result of
 * that run and the optional version-control provenance (commit identifier and metadata). Together
 * these three pieces of information are sufficient to reproduce or audit any past execution.
 *
 * <p>The {@code commitId} and {@code commitInfo} fields are optional. They are non-null when the
 * execution was started from a stored checkpoint (the common production path). They are {@code
 * null} when the execution was triggered from the live editor state outside of version control
 * (testing or manual exploration).
 *
 * @param snapshot immutable workflow snapshot that was executed
 * @param result terminal outcome of the execution run
 * @param commitId version-control identifier of the stored checkpoint (may be {@code null})
 * @param commitInfo author, message and timestamp of the stored checkpoint (may be {@code null})
 */
public record ReproducibilityBundle(
    ExecutionSnapshot snapshot, ExecutionResult result, CommitId commitId, CommitInfo commitInfo) {

  public ReproducibilityBundle {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(result, "result");
    if (commitInfo != null) {
      if (commitId == null) {
        throw new IllegalArgumentException("commitId must not be null when commitInfo is present");
      }
      if (!commitId.equals(commitInfo.commitId())) {
        throw new IllegalArgumentException(
            "commitId must equal commitInfo.commitId() but got commitId="
                + commitId
                + " and commitInfo.commitId()="
                + commitInfo.commitId());
      }
    }
  }

  /**
   * Returns {@code true} if this bundle carries version-control provenance, i.e. both {@link
   * #commitId()} and {@link #commitInfo()} are non-null.
   */
  public boolean hasStoredProvenance() {
    return commitId != null && commitInfo != null;
  }
}
