package org.hammer.audio.workflow.search;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Replaceable search projection for one durable workflow checkpoint. */
public record WorkflowHistoryDocument(
    String branch,
    String commitId,
    String workflowId,
    String author,
    String message,
    Instant timestamp,
    Set<String> nodeTypes,
    Map<String, String> properties,
    String searchableText) {

  public WorkflowHistoryDocument {
    requireNotBlank(branch, "branch");
    requireNotBlank(commitId, "commitId");
    requireNotBlank(workflowId, "workflowId");
    requireNotBlank(author, "author");
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(timestamp, "timestamp");
    nodeTypes = Set.copyOf(nodeTypes);
    properties = Map.copyOf(properties);
    Objects.requireNonNull(searchableText, "searchableText");
  }

  private static void requireNotBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
