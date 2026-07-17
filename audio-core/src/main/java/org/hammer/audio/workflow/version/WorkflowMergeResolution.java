package org.hammer.audio.workflow.version;

import java.time.Instant;
import java.util.Objects;

/** Auditable semantic merge decision; it is persisted with the resulting checkpoint. */
public record WorkflowMergeResolution(
    String conflictId, Choice choice, String actorId, Instant resolvedAt) {

  public WorkflowMergeResolution {
    Objects.requireNonNull(conflictId, "conflictId");
    Objects.requireNonNull(choice, "choice");
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(resolvedAt, "resolvedAt");
  }

  public enum Choice {
    BASE,
    LOCAL,
    REMOTE,
    DELETE
  }
}
