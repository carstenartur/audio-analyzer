package org.hammer.audio.workflow.history;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.merge.WorkflowMergeResolution;
import org.hammer.audio.workflow.store.CommitMetadata;

/** Encodes deterministic merge provenance through the established commit-message contract. */
final class WorkflowMergeAuditTrail {

  private WorkflowMergeAuditTrail() {
    throw new UnsupportedOperationException("Utility class");
  }

  static CommitMetadata metadata(
      PreviewWorkflowMergeCommand command,
      List<WorkflowMergeResolution> resolutions,
      CommitMetadata requested) {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(resolutions, "resolutions");
    Objects.requireNonNull(requested, "requested");
    return new CommitMetadata(
        requested.author(),
        message(command, resolutions, requested.message()),
        requested.timestamp());
  }

  static String message(
      PreviewWorkflowMergeCommand command,
      List<WorkflowMergeResolution> resolutions,
      String userMessage) {
    StringBuilder audit = new StringBuilder(requireText(userMessage, "userMessage"));
    audit.append("\n\n[workflow-merge]\n");
    append(audit, "targetBranch", command.targetBranch());
    append(audit, "remoteBranch", command.remoteBranch());
    append(audit, "base", command.baseCommit().value());
    append(audit, "local", command.localCommit().value());
    append(audit, "remote", command.remoteCommit().value());
    List<WorkflowMergeResolution> ordered =
        resolutions.stream()
            .sorted(Comparator.comparing(WorkflowMergeResolution::conflictId))
            .toList();
    for (int index = 0; index < ordered.size(); index++) {
      WorkflowMergeResolution resolution = ordered.get(index);
      String prefix = "resolution." + index + ".";
      append(audit, prefix + "conflict", resolution.conflictId());
      append(audit, prefix + "choice", resolution.choice().name());
      if (resolution.customValue() != null) {
        append(audit, prefix + "custom", resolution.customValue());
      }
    }
    return audit.toString();
  }

  private static void append(StringBuilder target, String key, String value) {
    target.append(key).append('=').append(escape(value)).append('\n');
  }

  private static String escape(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("=", "\\=");
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
