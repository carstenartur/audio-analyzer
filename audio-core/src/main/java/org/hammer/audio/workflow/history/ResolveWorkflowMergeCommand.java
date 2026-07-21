package org.hammer.audio.workflow.history;

import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.merge.WorkflowMergeResolution;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitMetadata;

/**
 * Exact merge identities, decisions and audit metadata used to create one resolved checkpoint.
 *
 * @param preview exact commits and branches used by the preview
 * @param expectedHead target-branch HEAD protected by optimistic concurrency
 * @param resolutions immutable explicit semantic conflict decisions
 * @param metadata user-facing author/message/timestamp for the resulting merge checkpoint
 */
public record ResolveWorkflowMergeCommand(
    PreviewWorkflowMergeCommand preview,
    CommitId expectedHead,
    List<WorkflowMergeResolution> resolutions,
    CommitMetadata metadata) {

  public ResolveWorkflowMergeCommand {
    Objects.requireNonNull(preview, "preview");
    Objects.requireNonNull(expectedHead, "expectedHead");
    resolutions = List.copyOf(Objects.requireNonNull(resolutions, "resolutions"));
    Objects.requireNonNull(metadata, "metadata");
    if (!expectedHead.equals(preview.localCommit())) {
      throw new IllegalArgumentException(
          "expectedHead must equal the exact local commit used by the merge preview");
    }
  }
}
