package org.hammer.audio.workflow.history;

/**
 * Full-text workflow history query with a bounded result count.
 *
 * @param text user-entered full-text expression; blank selects the latest indexed commits
 * @param limit maximum result count between 1 and 200
 */
public record WorkflowHistoryTextQuery(String text, int limit) {

  public WorkflowHistoryTextQuery {
    text = text == null ? "" : text.trim();
    if (limit <= 0 || limit > 200) {
      throw new IllegalArgumentException("limit must be between 1 and 200");
    }
  }
}
