package org.hammer.audio.workflow.history;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.store.CommitId;

/**
 * One indexed search hit that can load the exact authoritative workflow commit.
 *
 * @param commitId exact authoritative Git commit identity
 * @param shortMessage commit summary indexed by the shared search projection
 * @param authorName commit author display name, or {@code null} when unavailable
 * @param authorEmail commit author email, or {@code null} when unavailable
 * @param timestamp commit author timestamp, or {@code null} when unavailable
 * @param changedPaths first-parent changed paths represented by this projection
 */
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
