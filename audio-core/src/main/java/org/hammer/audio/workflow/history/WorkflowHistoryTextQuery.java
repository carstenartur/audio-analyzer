package org.hammer.audio.workflow.history;

import java.time.Instant;

/**
 * Bounded indexed workflow-history query using only application-facing value types.
 *
 * @param text user-entered full-text expression; blank selects the latest indexed commits
 * @param authorEmail optional exact author email
 * @param pathText optional analyzed changed-path expression
 * @param from optional inclusive lower commit-time bound
 * @param to optional inclusive upper commit-time bound
 * @param limit maximum result count between 1 and 200
 */
public record WorkflowHistoryTextQuery(
    String text,
    String authorEmail,
    String pathText,
    Instant from,
    Instant to,
    int limit) {

  public WorkflowHistoryTextQuery {
    text = normalize(text, "");
    authorEmail = normalize(authorEmail, null);
    pathText = normalize(pathText, null);
    if (from != null && to != null && from.isAfter(to)) {
      throw new IllegalArgumentException("from must not be after to");
    }
    if (limit <= 0 || limit > 200) {
      throw new IllegalArgumentException("limit must be between 1 and 200");
    }
  }

  /** Preserves the original full-text-only construction API. */
  public WorkflowHistoryTextQuery(String text, int limit) {
    this(text, null, null, null, null, limit);
  }

  private static String normalize(String value, String emptyValue) {
    if (value == null || value.isBlank()) {
      return emptyValue;
    }
    return value.trim();
  }
}
