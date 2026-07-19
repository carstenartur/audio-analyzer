package org.hammer.audio.workflow.history;

/** Full-text workflow history query with a bounded result count. */
public record WorkflowHistoryTextQuery(String text, int limit) {

  public WorkflowHistoryTextQuery {
    text = text == null ? "" : text.trim();
    if (limit <= 0 || limit > 200) {
      throw new IllegalArgumentException("limit must be between 1 and 200");
    }
  }
}
