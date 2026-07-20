package org.hammer.audio.workflow.history;

import java.util.Objects;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitMetadata;

/** Explicit non-destructive command that restores a historical snapshot as a new branch commit. */
public record RestoreWorkflowVersionCommand(
    String branch, CommitId targetCommit, CommitId expectedHead, CommitMetadata metadata) {

  public RestoreWorkflowVersionCommand {
    branch = requireNotBlank(branch, "branch");
    Objects.requireNonNull(targetCommit, "targetCommit");
    Objects.requireNonNull(expectedHead, "expectedHead");
    Objects.requireNonNull(metadata, "metadata");
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    String normalized = value.trim();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return normalized;
  }
}
