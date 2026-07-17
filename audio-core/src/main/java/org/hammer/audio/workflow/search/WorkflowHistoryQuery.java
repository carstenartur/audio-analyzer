package org.hammer.audio.workflow.search;

import java.time.Instant;

/** Search filters independent of a concrete index engine. */
public record WorkflowHistoryQuery(
    String text,
    String branch,
    String author,
    Instant from,
    Instant to,
    String nodeType,
    String propertyKey,
    String propertyValue,
    int limit) {

  public WorkflowHistoryQuery {
    if (limit < 1 || limit > 1000) {
      throw new IllegalArgumentException("limit must be between 1 and 1000");
    }
  }
}
