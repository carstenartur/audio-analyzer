package org.hammer.audio.workflow.history;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.store.CommitId;

/** One indexed search hit that can load the exact authoritative workflow commit. */
public record WorkflowHistoryTextResult(
    CommitId commitId,
    String shortMessage,
    String authorName,
    String authorEmail,
    Instant timestamp,
    List<String> changedPaths) {

  public WorkflowHistoryTextResult {
    Objects.requireNonNull(commitId, "commitId");
    shortMessage = shortMessage == null ? "" : shortMessage;
    changedPaths = List.copyOf(Objects.requireNonNull(changedPaths, "changedPaths"));
  }
}
